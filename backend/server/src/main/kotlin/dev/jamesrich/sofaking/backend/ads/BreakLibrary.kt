package dev.jamesrich.sofaking.backend.ads

import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.adBreaks
import dev.jamesrich.sofaking.backend.store.deleteAdBreaks
import dev.jamesrich.sofaking.backend.store.saveAdBreaks
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * One stretch of ad audio the library learned: its Chromaprint [points], one per [POINT_MS], and where it came from.
 * [channels] and [days] are where and when markers placed it in a break; [hits] and [falseHits] count the canary's
 * checks of it on marked channels, and [blocked] is set by a false hit. An entry may blank a break once it was marked
 * on two channels or two days and never matched show audio ([blanking]); until then its matches are logged only.
 */
class BreakEntry(
    val id: String,
    val points: IntArray,
    val providerId: String,
    /** The break it was first learned from, so a session never matches the break it learned. */
    val source: String,
    val createdAt: Instant,
    var channels: Set<String> = emptySet(),
    var days: Set<String> = emptySet(),
    var lastHitAt: Instant? = null,
    var hits: Int = 0,
    var falseHits: Int = 0,
    var blocked: Boolean = false,
) {
    val blanking: Boolean get() = !blocked && falseHits == 0 && (channels.size >= 2 || days.size >= 2)

    fun copy() =
        BreakEntry(id, points, providerId, source, createdAt, channels, days, lastHitAt, hits, falseHits, blocked)
}

/** Where [query] points [queryFrom] to [queryTo] (inclusive) match [entry] from its point [entryFrom]. */
class BreakMatch(val entry: BreakEntry, val queryFrom: Int, val queryTo: Int, val entryFrom: Int) {
    val points: Int get() = queryTo - queryFrom + 1

    /** Points of the entry left after the match. */
    val entryLeft: Int get() = entry.points.size - (entryFrom + points)

    /** The longer match wins; a blocked entry wins a tie, since show audio must veto. */
    fun beats(other: BreakMatch): Boolean =
        points > other.points || (points == other.points && entry.blocked && !other.entry.blocked)
}

/** The library's size and how the canary has gone over the last day. */
data class LibraryStats(
    val entries: Int,
    val blanking: Int,
    val blocked: Int,
    val points: Long,
    val canaryHits: Int,
    val canaryFalse: Int,
    val frozen: Boolean,
) {
    val precision: Double? get() = if (canaryHits + canaryFalse == 0) {
        null
    } else {
        canaryHits.toDouble() / (canaryHits + canaryFalse)
    }
}

/**
 * The ads the server has heard, so it knows them again on a channel that marks nothing. Breaks a provider's markers
 * opened and closed are fingerprinted with Chromaprint (see `SegmentAudio`) and kept here, a few seconds in from each
 * edge; audio already known adds its channel and day to the entry it matched instead of a new one. A session's recent
 * points are matched against the library the way the intro matcher does it (see `IntroMatch`): exact words vote for
 * an entry and a shift, and the best few are checked point by point, at most [MAX_BIT_ERRORS] bits apart, for a run
 * with no gap over [MAX_GAP_POINTS] and [MIN_DENSITY] of its points matching.
 *
 * On marked channels every match is checked against the markers, the canary: a match in a marked break is a hit, one
 * in show audio well away from any break is false, and blocks its entry for good, so its audio vetoes later matches.
 * Under [CANARY_PRECISION] over the last day, no learned break is marked anywhere ([frozen]).
 *
 * Entries live in memory, indexed by word, and are saved by [flush]; one not marked or matched for [EXPIRY] goes.
 */
class BreakLibrary(private val store: BackendStore?, private val clock: Clock) {
    private val entries = ArrayList<BreakEntry?>()
    private val slots = HashMap<String, Int>()
    private val index = HashMap<Int, Postings>()
    private val dirty = HashSet<String>()
    private val removed = HashSet<String>()
    private val canary = ArrayDeque<Pair<Instant, Boolean>>()
    private var pointCount = 0L
    private var deadSlots = 0

    private class Postings {
        var data = IntArray(2)
        var size = 0
        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }
    }

    /** Reads the saved entries. */
    suspend fun load() {
        val saved = store?.adBreaks().orEmpty()
        synchronized(this) { saved.forEach(::add) }
    }

    /** Saves what changed and drops the entries that expired. */
    suspend fun flush() {
        val (changed, gone) = synchronized(this) {
            expire()
            val changed = dirty.mapNotNull { id -> slots[id]?.let { entries[it]?.copy() } }
            val gone = removed.toList()
            dirty.clear()
            removed.clear()
            changed to gone
        }
        store?.let {
            if (gone.isNotEmpty()) it.deleteAdBreaks(gone)
            if (changed.isNotEmpty()) it.saveAdBreaks(changed)
        }
    }

    @Synchronized
    fun stats(): LibraryStats {
        trimCanary()
        val live = entries.filterNotNull()
        return LibraryStats(
            live.size,
            live.count { it.blanking },
            live.count { it.blocked },
            pointCount,
            canary.count { it.second },
            canary.count { !it.second },
            frozenNow(),
        )
    }

    /** Whether the canary's last day is under [CANARY_PRECISION], so no learned break is marked. */
    @Synchronized
    fun frozen(): Boolean {
        trimCanary()
        return frozenNow()
    }

    private fun frozenNow(): Boolean {
        val wrong = canary.count { !it.second }
        return wrong > 0 && (canary.size - wrong).toDouble() / canary.size < CANARY_PRECISION
    }

    /**
     * Learns [points], the audio of a break markers opened and closed on [channelId], from [source]: what matches an
     * entry counts as that entry marked again, and each stretch of [MIN_LEARN_POINTS] or more that matches nothing
     * becomes an entry of its own.
     */
    @Synchronized
    fun learn(points: IntArray, channelId: String, providerId: String, source: String) {
        val now = clock.instant()
        val day = now.atOffset(ZoneOffset.UTC).toLocalDate().toString()
        val known = BooleanArray(points.size)
        for (round in 1..MAX_LEARN_MATCHES) {
            val match = best(points, MIN_LEARN_POINTS, known, exclude = null) ?: break
            for (i in match.queryFrom..match.queryTo) known[i] = true
            val entry = match.entry
            if (!entry.blocked && (channelId !in entry.channels || day !in entry.days)) {
                entry.channels += channelId
                entry.days += day
                dirty += entry.id
            }
        }
        var from = -1
        for (i in 0..points.size) {
            val free = i < points.size && !known[i]
            if (free && from < 0) from = i
            if (!free && from >= 0) {
                if (i - from >= MIN_LEARN_POINTS) {
                    var start = from
                    while (start < i) {
                        val end = minOf(i, start + MAX_ENTRY_POINTS)
                        if (end - start >= MIN_LEARN_POINTS) {
                            val entry =
                                BreakEntry(
                                    UUID.randomUUID().toString(),
                                    points.copyOfRange(start, end),
                                    providerId,
                                    source,
                                    now,
                                    setOf(channelId),
                                    setOf(day),
                                )
                            add(entry)
                            dirty += entry.id
                        }
                        start = end
                    }
                }
                from = -1
            }
        }
        if (pointCount > MAX_POINTS) evict()
    }

    /**
     * The longest match of [query] of [minPoints] or more, against any entry not learned by the clock tagged [exclude].
     */
    @Synchronized
    fun match(query: IntArray, minPoints: Int, exclude: String? = null): BreakMatch? =
        best(query, minPoints, null, exclude)

    /** The canary found [entry] in a marked break on [channelId]. */
    @Synchronized
    fun hit(entry: BreakEntry) {
        entry.hits++
        entry.lastHitAt = clock.instant()
        dirty += entry.id
        canary.addLast(clock.instant() to true)
        trimCanary()
    }

    /** The canary found [entry] in show audio: it is blocked, and its audio vetoes matches from now on. */
    @Synchronized
    fun falseHit(entry: BreakEntry) {
        entry.falseHits++
        entry.blocked = true
        dirty += entry.id
        canary.addLast(clock.instant() to false)
        trimCanary()
    }

    /** A session matched [entry] on a channel without markers. */
    @Synchronized
    fun matched(entry: BreakEntry) {
        entry.lastHitAt = clock.instant()
        dirty += entry.id
    }

    private fun trimCanary() {
        val since = clock.instant().minus(CANARY_WINDOW)
        while (canary.isNotEmpty() && canary.first().first.isBefore(since)) canary.removeFirst()
    }

    private fun add(entry: BreakEntry) {
        val slot = entries.size
        entries += entry
        slots[entry.id] = slot
        entry.points.forEachIndexed { position, word ->
            index.getOrPut(word) { Postings() }.add((slot shl POSITION_BITS) or position)
        }
        pointCount += entry.points.size
    }

    private fun remove(slot: Int) {
        val entry = entries[slot] ?: return
        entries[slot] = null
        slots -= entry.id
        dirty -= entry.id
        removed += entry.id
        pointCount -= entry.points.size
        deadSlots++
    }

    private fun expire() {
        val before = clock.instant().minus(EXPIRY)
        entries.indices.filter { slot ->
            entries[slot]?.let { (it.lastHitAt ?: it.createdAt).isBefore(before) && lastMarked(it).isBefore(before) } ==
                true
        }.forEach(::remove)
        if (deadSlots > entries.size / 4) rebuild()
    }

    private fun lastMarked(entry: BreakEntry): Instant = entry.days.maxOrNull()?.let {
        java.time.LocalDate.parse(it).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
    }
        ?: entry.createdAt

    /** Drops the least recently heard entries until the library is back under [MAX_POINTS]. */
    private fun evict() {
        val order = entries.indices.filter { entries[it] != null && !entries[it]!!.blocked }
            .sortedBy { slot -> entries[slot]!!.let { maxOf(it.lastHitAt ?: it.createdAt, lastMarked(it)) } }
        for (slot in order) {
            if (pointCount <= MAX_POINTS * 9 / 10) break
            remove(slot)
        }
        rebuild()
    }

    private fun rebuild() {
        val live = entries.filterNotNull()
        entries.clear()
        slots.clear()
        index.clear()
        pointCount = 0
        deadSlots = 0
        live.forEach(::add)
    }

    private fun best(query: IntArray, minPoints: Int, skip: BooleanArray?, exclude: String?): BreakMatch? {
        if (query.size < minPoints || index.isEmpty()) return null
        val votes = HashMap<Long, Int>()
        for (i in query.indices) {
            if (skip?.get(i) == true) continue
            val postings = index[query[i]] ?: continue
            // A word on very many entries (near silence) says nothing about which.
            if (postings.size > COMMON_WORD) continue
            for (k in 0 until postings.size) {
                val posting = postings.data[k]
                val slot = posting ushr POSITION_BITS
                val shift = (posting and POSITION_MASK) - i
                votes.merge((slot.toLong() shl 32) or (shift.toLong() and 0xFFFFFFFFL), 1, Int::plus)
            }
        }
        var best: BreakMatch? = null
        for ((key, count) in votes.entries.sortedByDescending { it.value }.take(CANDIDATES)) {
            if (count < MIN_VOTES) break
            val entry = entries[(key ushr 32).toInt()] ?: continue
            if (exclude != null && entry.source.startsWith("$exclude:")) continue
            val shift = key.toInt()
            for (nudge in -1..1) {
                val run = longestRun(query, entry.points, shift + nudge, minPoints, skip) ?: continue
                val match = BreakMatch(entry, run.first, run.last, run.first + shift + nudge)
                if (best == null || match.beats(best)) best = match
            }
        }
        return best
    }

    /** With [entry] shifted [shift] points against [query], the longest run that passes the bounds. */
    private fun longestRun(
        query: IntArray,
        entry: IntArray,
        shift: Int,
        minPoints: Int,
        skip: BooleanArray?,
    ): IntRange? {
        val from = maxOf(0, -shift)
        val until = minOf(query.size, entry.size - shift)
        var best: IntRange? = null
        var start = -1
        var last = -1
        var matches = 0
        fun close() {
            if (start < 0) return
            val length = last - start + 1
            val longest = best?.let { length > it.count() } ?: true
            if (length >= minPoints && matches >= MIN_DENSITY * length && longest) best = start..last
        }
        for (i in from until until) {
            if (skip?.get(i) == true || Integer.bitCount(query[i] xor entry[i + shift]) > MAX_BIT_ERRORS) continue
            if (start >= 0 && i - last > MAX_GAP_POINTS) {
                close()
                start = -1
            }
            if (start < 0) {
                start = i
                matches = 0
            }
            last = i
            matches++
        }
        close()
        return best
    }

    companion object {
        /** One Chromaprint point covers a third of its 4096-sample frame at 11,025 Hz. */
        const val POINT_MS = 4096.0 / 3 / 11_025 * 1_000
        const val MAX_BIT_ERRORS = 6
        const val MIN_DENSITY = 0.6

        /** About 2 s. */
        const val MAX_GAP_POINTS = 16

        /** About 10 s: the least a learned stretch, or a match that starts a break, may be. */
        const val MIN_LEARN_POINTS = 81

        /** About 4 minutes, which also bounds a position to [POSITION_BITS]. */
        const val MAX_ENTRY_POINTS = 1_940
        const val CANARY_PRECISION = 0.999
        val CANARY_WINDOW: Duration = Duration.ofDays(1)
        val EXPIRY: Duration = Duration.ofDays(30)

        /** About 3 days of ads heard without a break; past it the least recently heard go. */
        const val MAX_POINTS = 2_000_000L
        private const val POSITION_BITS = 12
        private const val POSITION_MASK = (1 shl POSITION_BITS) - 1
        private const val COMMON_WORD = 2_000
        private const val CANDIDATES = 8
        private const val MIN_VOTES = 3
        private const val MAX_LEARN_MATCHES = 16
    }
}
