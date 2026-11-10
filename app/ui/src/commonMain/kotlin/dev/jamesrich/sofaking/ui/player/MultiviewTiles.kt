package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.PlaybackPreferences
import dev.jamesrich.sofaking.ui.ChannelSearch

/** The fewest channels a multiview shows. */
internal const val MULTIVIEW_MIN_TILES = 2

/**
 * Where tile [index] of [count] sits on the screen, in fractions of its width and height: one fills it, two sit side
 * by side across the middle, three or four make a grid, left to right and top to bottom.
 */
internal fun multiviewTile(count: Int, index: Int): Rect {
    if (count < MULTIVIEW_MIN_TILES) return Rect(0f, 0f, 1f, 1f)
    val left = (index % 2) * HALF
    val top = if (count == MULTIVIEW_MIN_TILES) QUARTER else (index / 2) * HALF
    return Rect(left, top, left + HALF, top + HALF)
}

/** The tile of [count] at [x] and [y], fractions of the screen's width and height; null between and around them. */
internal fun multiviewTileAt(count: Int, x: Float, y: Float): Int? =
    (0 until count).firstOrNull { multiviewTile(count, it).contains(Offset(x, y)) }

/** The tile an arrow [key] moves the sound to from [from], of [count]; [from] itself when nothing lies that way. */
internal fun multiviewMove(count: Int, from: Int, key: Key): Int {
    val to = when (key) {
        Key.DirectionLeft -> if (from % 2 == 1) from - 1 else from
        Key.DirectionRight -> if (from % 2 == 0) from + 1 else from
        Key.DirectionUp -> if (count > MULTIVIEW_MIN_TILES && from >= 2) from - 2 else from
        Key.DirectionDown -> if (count > MULTIVIEW_MIN_TILES && from < 2) from + 2 else from
        else -> from
    }
    return to.takeIf { it < count } ?: from
}

/**
 * The player each of [picked] plays on, numbered from 0: a channel keeps the one [held] gave it, so its stream plays
 * on while the others come and go, and a new channel takes the lowest one free.
 */
internal fun paneSlots(held: Map<String, Int>, picked: List<String>): Map<String, Int> {
    val kept = held.filterKeys { it in picked }
    val free = (0 until Wire.MULTIVIEW_MAX_TILES).filter { it !in kept.values }.iterator()
    return picked.associateWith { kept[it] ?: free.next() }
}

/** The channel whose sound plays once [picked] is what shows: [sound] while it is still there, else the first. */
internal fun soundAfter(sound: String?, picked: List<String>): String? =
    sound?.takeIf { it in picked } ?: picked.firstOrNull()

/**
 * What every pane of a multiview takes from Settings: the captions and their look, what an ad break does, and the
 * decoder and buffer choices.
 */
@Immutable
internal data class PaneLook(
    val captions: Boolean = false,
    val captionStyle: CaptionStyle = CaptionStyle.SYSTEM,
    val breaks: LiveBreaks = LiveBreaks.PLAY,
    val playback: PlaybackPreferences = PlaybackPreferences(),
)

/** Whether a pane draws captions: always while it is silent, by the setting while its [sound] plays. */
internal fun paneCaptions(sound: Boolean, setting: Boolean): Boolean = !sound || setting

/** Whether a pane is silent: another has the [sound], or the break screen covers it and mutes ([breaks]). */
internal fun paneSilent(sound: Boolean, covered: Boolean, breaks: LiveBreaks): Boolean =
    !sound || (covered && breaks.muted)

/** A heading of the picker's list. */
internal enum class PickSection { WATCHING, FAVORITES, RECENT, ALL, RESULTS }

/** The channels under one heading of the picker's list. */
internal data class PickGroup(val section: PickSection, val channels: List<Channel>)

/**
 * The picker's list: the channels multiview opened on ([opened]), the starred ones in [favoritesOrder], the [recent]
 * ones, then the rest of [channels] in lineup order, each once and under its heading. With a [query], the channels it
 * matches by number, name or the program [airing] on them, under one heading.
 */
internal fun multiviewGroups(
    channels: List<Channel>,
    opened: List<String>,
    favoritesOrder: List<String>,
    recent: List<String>,
    query: String = "",
    airing: (Channel) -> String? = { null },
): List<PickGroup> {
    if (query.isNotBlank()) {
        val found = channels.filter { ChannelSearch.matches(query, it.number, it.name, airing(it)) }
        return listOf(PickGroup(PickSection.RESULTS, found))
    }
    val byId = channels.associateBy { it.guideId }
    val listed = HashSet<String>()
    fun group(section: PickSection, ids: List<String>) =
        PickGroup(section, ids.filter { it in byId && listed.add(it) }.mapNotNull { byId[it] })
    return listOf(
        group(PickSection.WATCHING, opened),
        group(PickSection.FAVORITES, favoritesOrder),
        group(PickSection.RECENT, recent),
        group(PickSection.ALL, channels.map { it.guideId }),
    ).filter { it.channels.isNotEmpty() }
}

/** [picked] with [guideId] added or taken out; a pick past [Wire.MULTIVIEW_MAX_TILES] is refused. */
internal fun togglePick(picked: List<String>, guideId: String): List<String> = when {
    guideId in picked -> picked - guideId
    picked.size >= Wire.MULTIVIEW_MAX_TILES -> picked
    else -> picked + guideId
}

private const val HALF = 0.5f
private const val QUARTER = 0.25f
