package dev.jamesrich.sofaking.data

import kotlin.time.Instant

/**
 * A channel a search found, with the [program] it was found by: the one airing now, which is null when the guide has
 * nothing for the channel, or one coming up.
 */
data class LiveHit(val channel: Channel, val program: Program?) {
    /** Names the hit among a search's results, so a page can give focus back to it. */
    val key: String get() = "live:${channel.guideId}:${program?.start?.epochSeconds ?: 0}"
}

/** What a search finds in live TV: the channels to watch [now], and the programs coming up [later]. */
data class LiveSearchResults(val now: List<LiveHit> = emptyList(), val later: List<LiveHit> = emptyList()) {
    val found: Boolean get() = now.isNotEmpty() || later.isNotEmpty()

    /** The first hit on [guideId]'s channel, the one a page focuses as the viewer comes back from watching it. */
    fun onChannel(guideId: String): LiveHit? = (now + later).firstOrNull { it.channel.guideId == guideId }
}

/** The most hits [Lineup.search] lists in each of its rows. */
const val LIVE_SEARCH_LIMIT = 20

/**
 * Live TV's answer to [query], ignoring case and accents: [LiveSearchResults.now] holds the channels whose number
 * starts with it or whose name holds it, in lineup order, then those whose program airing at [at] holds it in its
 * title or episode title; [LiveSearchResults.later] holds the programs starting after [at] that do, soonest first.
 * Each list stops at [limit]. A blank query finds nothing.
 */
fun Lineup.search(query: String, at: Instant, limit: Int = LIVE_SEARCH_LIMIT): LiveSearchResults {
    val needle = SearchText.fold(query.trim())
    if (needle.isEmpty()) return LiveSearchResults()
    val named = mutableListOf<LiveHit>()
    val airing = mutableListOf<LiveHit>()
    val later = mutableListOf<LiveHit>()
    for (channel in channels.distinctBy { it.guideId }) {
        val programs = guide[channel.guideId].orEmpty()
        val current = programs.firstOrNull { it.start <= at && it.stop > at }
        when {
            channel.matches(needle) -> named += LiveHit(channel, current)
            current != null && current.matches(needle) -> airing += LiveHit(channel, current)
        }
        programs.filter { it.start > at && it.matches(needle) }.mapTo(later) { LiveHit(channel, it) }
    }
    return LiveSearchResults(
        now = (named + airing).take(limit),
        later = later.sortedWith(compareBy({ it.program?.start }, { it.channel.number })).take(limit),
    )
}

private fun Channel.matches(needle: String): Boolean =
    SearchText.fold(numberText).startsWith(needle) || SearchText.fold(name).contains(needle)

private fun Program.matches(needle: String): Boolean =
    SearchText.fold(title).contains(needle) || subTitle?.let { SearchText.fold(it).contains(needle) } == true

/** The channel number as the guide shows it: `5`, or `5.1` for a subchannel. */
private val Channel.numberText: String get() = if (number % 1.0 ==
    0.0
) {
    number.toLong().toString()
} else {
    number.toString()
}

/** Text as a search compares it. */
object SearchText {
    /** Lowercased with accents removed, so "Télé" and "tele" compare equal. */
    fun fold(text: String): String = buildString(text.length) {
        for (char in text.lowercase()) {
            // Combining marks (a decomposed accent) are dropped; precomposed letters fold to their base letter.
            if (char in '̀'..'ͯ') continue
            append(FOLDS[char] ?: char)
        }
    }

    /**
     * The Latin letters with diacritics that NFD would decompose, folded to their base letter; Kotlin has no
     * common Unicode normalizer. Covers Latin-1 Supplement and Latin Extended-A, which is what channel and
     * program names use.
     */
    private val FOLDS: Map<Char, Char> = buildMap {
        fun fold(base: Char, accented: String) = accented.forEach { put(it, base) }
        fold('a', "àáâãäåāăą")
        fold('c', "çćĉċč")
        fold('d', "ď")
        fold('e', "èéêëēĕėęě")
        fold('g', "ĝğġģ")
        fold('h', "ĥ")
        fold('i', "ìíîïĩīĭįİ")
        fold('j', "ĵ")
        fold('k', "ķ")
        fold('l', "ĺļľ")
        fold('n', "ñńņňŉ")
        fold('o', "òóôõöōŏő")
        fold('r', "ŕŗř")
        fold('s', "śŝşš")
        fold('t', "ţť")
        fold('u', "ùúûüũūŭůűų")
        fold('w', "ŵ")
        fold('y', "ýÿŷ")
        fold('z', "źżž")
    }
}
