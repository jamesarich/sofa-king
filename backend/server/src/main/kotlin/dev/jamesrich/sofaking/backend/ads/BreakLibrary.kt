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
    private val index = BreakIndex()
    private val changes = Changes()
    private val canary = Canary(clock)

    /** Reads the saved entries. */
    suspend fun load() {
        val saved = store?.adBreaks().orEmpty()
        synchronized(this) { saved.forEach(index::add) }
    }

    /** Saves what changed and drops the entries that expired. */
    suspend fun flush() {
        val (changed, gone) = synchronized(this) {
            expire()
            val (dirty, gone) = changes.drain()
            dirty.mapNotNull { index.entry(it)?.copy() } to gone
        }
        store?.let {
            if (gone.isNotEmpty()) it.deleteAdBreaks(gone)
            if (changed.isNotEmpty()) it.saveAdBreaks(changed)
        }
    }

    @Synchronized
    fun stats(): LibraryStats {
        val live = index.live
        val (hits, wrong) = canary.counts()
        return LibraryStats(
            live.size,
            live.count { it.blanking },
            live.count { it.blocked },
            index.pointCount,
            hits,
            wrong,
            canary.frozen(),
        )
    }

    /** Whether the canary's last day is under [CANARY_PRECISION], so no learned break is marked. */
    @Synchronized
    fun frozen(): Boolean = canary.frozen()

    /**
     * Learns [points], the audio of a break markers opened and closed on [channelId], from [source]: what matches an
     * entry counts as that entry marked again, and each stretch of [MIN_LEARN_POINTS] or more that matches nothing
     * becomes an entry of its own.
     */
    @Synchronized
    fun learn(points: IntArray, channelId: String, providerId: String, source: String) {
        val now = clock.instant()
        val day = now.atOffset(ZoneOffset.UTC).toLocalDate().toString()
        val known = markKnown(points, channelId, day)
        for (run in freeRuns(known, MIN_LEARN_POINTS)) {
            for (start in run step MAX_ENTRY_POINTS) {
                val end = minOf(run.last + 1, start + MAX_ENTRY_POINTS)
                if (end - start >= MIN_LEARN_POINTS) {
                    val entry = BreakEntry(
                        UUID.randomUUID().toString(),
                        points.copyOfRange(start, end),
                        providerId,
                        source,
                        now,
                        setOf(channelId),
                        setOf(day),
                    )
                    index.add(entry)
                    changes.touched(entry.id)
                }
            }
        }
        if (index.pointCount > MAX_POINTS) changes.removed(index.evict(EVICT_TO))
    }

    /** Marks again each entry [points] match on [channelId] and [day]; true at each point a match covers. */
    private fun markKnown(points: IntArray, channelId: String, day: String): BooleanArray {
        val known = BooleanArray(points.size)
        for (round in 1..MAX_LEARN_MATCHES) {
            val match = index.best(points, MIN_LEARN_POINTS, known, exclude = null) ?: break
            for (i in match.queryFrom..match.queryTo) known[i] = true
            val entry = match.entry
            if (!entry.blocked && (channelId !in entry.channels || day !in entry.days)) {
                entry.channels += channelId
                entry.days += day
                changes.touched(entry.id)
            }
        }
        return known
    }

    /**
     * The longest match of [query] of [minPoints] or more, against any entry not learned by the clock tagged [exclude].
     */
    @Synchronized
    fun match(query: IntArray, minPoints: Int, exclude: String? = null): BreakMatch? =
        index.best(query, minPoints, null, exclude)

    /** The canary found [entry] in a marked break on [channelId]. */
    @Synchronized
    fun hit(entry: BreakEntry) {
        entry.hits++
        entry.lastHitAt = clock.instant()
        changes.touched(entry.id)
        canary.record(hit = true)
    }

    /** The canary found [entry] in show audio: it is blocked, and its audio vetoes matches from now on. */
    @Synchronized
    fun falseHit(entry: BreakEntry) {
        entry.falseHits++
        entry.blocked = true
        changes.touched(entry.id)
        canary.record(hit = false)
    }

    /** A session matched [entry] on a channel without markers. */
    @Synchronized
    fun matched(entry: BreakEntry) {
        entry.lastHitAt = clock.instant()
        changes.touched(entry.id)
    }

    private fun expire() {
        val before = clock.instant().minus(EXPIRY)
        changes.removed(
            index.removeWhere { (it.lastHitAt ?: it.createdAt).isBefore(before) && it.lastMarked().isBefore(before) },
        )
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

        /** Eviction goes on to nine tenths of [MAX_POINTS], so the next few adds do not evict again. */
        private const val EVICT_TO = MAX_POINTS * 9 / 10
        private const val MAX_LEARN_MATCHES = 16
    }
}

/** The index is rebuilt once more than this share of its slots hold removed entries. */
private const val MAX_DEAD_SHARE = 0.25
private const val POSITION_BITS = 12
private const val POSITION_MASK = (1 shl POSITION_BITS) - 1
private const val COMMON_WORD = 2_000
private const val CANDIDATES = 8
private const val MIN_VOTES = 3

/** The end of the last day markers placed this entry in a break, or when it was learned. */
private fun BreakEntry.lastMarked(): Instant = days.maxOrNull()?.let {
    java.time.LocalDate.parse(it).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
}
    ?: createdAt

/** The runs of at least [minLength] points [known] does not cover. */
private fun freeRuns(known: BooleanArray, minLength: Int): List<IntRange> {
    val runs = ArrayList<IntRange>()
    var from = -1
    for (i in 0..known.size) {
        val free = i < known.size && !known[i]
        if (free && from < 0) from = i
        if (!free && from >= 0) {
            if (i - from >= minLength) runs += from until i
            from = -1
        }
    }
    return runs
}

/** What changed since the last flush: the entries to save, and the ids of the entries to delete. */
private class Changes {
    private val dirty = HashSet<String>()
    private val removed = HashSet<String>()

    fun touched(id: String) {
        dirty += id
    }

    fun removed(ids: Collection<String>) {
        dirty -= ids.toSet()
        removed += ids
    }

    /** The ids changed and the ids removed, forgotten here once taken. */
    fun drain(): Pair<List<String>, List<String>> {
        val taken = dirty.toList() to removed.toList()
        dirty.clear()
        removed.clear()
        return taken
    }
}

/** The canary's checks over the last [BreakLibrary.CANARY_WINDOW]: each a hit, or a match in show audio. */
private class Canary(private val clock: Clock) {
    private val checks = ArrayDeque<Pair<Instant, Boolean>>()

    fun record(hit: Boolean) {
        checks.addLast(clock.instant() to hit)
        trim()
    }

    /** The hits and the false hits. */
    fun counts(): Pair<Int, Int> {
        trim()
        val hits = checks.count { it.second }
        return hits to checks.size - hits
    }

    /** Whether the checks are under [BreakLibrary.CANARY_PRECISION]. */
    fun frozen(): Boolean {
        val (hits, wrong) = counts()
        return wrong > 0 && hits.toDouble() / (hits + wrong) < BreakLibrary.CANARY_PRECISION
    }

    private fun trim() {
        val since = clock.instant().minus(BreakLibrary.CANARY_WINDOW)
        while (checks.isNotEmpty() && checks.first().first.isBefore(since)) checks.removeFirst()
    }
}

/**
 * The entries in memory, indexed by Chromaprint word: a word's postings hold each entry slot and position it has.
 * Matching works as [BreakLibrary] describes; the library holds the lock.
 */
private class BreakIndex {
    private val entries = ArrayList<BreakEntry?>()
    private val slots = HashMap<String, Int>()
    private val index = HashMap<Int, Postings>()
    var pointCount = 0L
        private set
    private var deadSlots = 0

    private class Postings {
        var data = IntArray(2)
        var size = 0
        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }
    }

    val live: List<BreakEntry> get() = entries.filterNotNull()

    fun entry(id: String): BreakEntry? = slots[id]?.let { entries[it] }

    fun add(entry: BreakEntry) {
        val slot = entries.size
        entries += entry
        slots[entry.id] = slot
        entry.points.forEachIndexed { position, word ->
            index.getOrPut(word) { Postings() }.add((slot shl POSITION_BITS) or position)
        }
        pointCount += entry.points.size
    }

    /** Removes the entries [drop] selects; returns their ids. */
    fun removeWhere(drop: (BreakEntry) -> Boolean): List<String> {
        val gone = entries.indices.filter { slot -> entries[slot]?.let(drop) == true }.mapNotNull(::remove)
        if (deadSlots > entries.size * MAX_DEAD_SHARE) rebuild()
        return gone
    }

    /** Drops the least recently heard unblocked entries until [to] points are left; returns their ids. */
    fun evict(to: Long): List<String> {
        val order = entries.indices.filter { entries[it]?.blocked == false }
            .sortedBy { slot -> entries[slot]!!.let { maxOf(it.lastHitAt ?: it.createdAt, it.lastMarked()) } }
        val gone = ArrayList<String>()
        for (slot in order) {
            if (pointCount <= to) break
            remove(slot)?.let(gone::add)
        }
        rebuild()
        return gone
    }

    /** The longest match of [query] of [minPoints] or more outside [skip], against entries not learned by [exclude]. */
    fun best(query: IntArray, minPoints: Int, skip: BooleanArray?, exclude: String?): BreakMatch? {
        if (query.size < minPoints || index.isEmpty()) return null
        return votes(query, skip).entries.sortedByDescending { it.value }.take(CANDIDATES)
            .takeWhile { it.value >= MIN_VOTES }
            .mapNotNull { (key, _) ->
                entries[(key ushr Int.SIZE_BITS).toInt()]
                    ?.takeUnless { exclude != null && it.source.startsWith("$exclude:") }
                    ?.let { it to key.toInt() }
            }.flatMap { (entry, shift) ->
                (-1..1).mapNotNull { nudge ->
                    longestRun(query, entry.points, shift + nudge, minPoints, skip)?.let { run ->
                        BreakMatch(entry, run.first, run.last, run.first + shift + nudge)
                    }
                }
            }.fold(null as BreakMatch?) { best, match -> if (best == null || match.beats(best)) match else best }
    }

    /** Each entry and shift the words of [query] outside [skip] vote for, with its votes. */
    private fun votes(query: IntArray, skip: BooleanArray?): Map<Long, Int> {
        val votes = HashMap<Long, Int>()
        for (i in query.indices) {
            // A word on very many entries (near silence) says nothing about which.
            val postings = index[query[i]]?.takeIf { skip?.get(i) != true && it.size <= COMMON_WORD } ?: continue
            for (k in 0 until postings.size) {
                val posting = postings.data[k]
                val slot = posting ushr POSITION_BITS
                val shift = (posting and POSITION_MASK) - i
                votes.merge(voteKey(slot, shift), 1, Int::plus)
            }
        }
        return votes
    }

    private fun remove(slot: Int): String? {
        val entry = entries[slot] ?: return null
        entries[slot] = null
        slots -= entry.id
        pointCount -= entry.points.size
        deadSlots++
        return entry.id
    }

    private fun rebuild() {
        val live = live
        entries.clear()
        slots.clear()
        index.clear()
        pointCount = 0
        deadSlots = 0
        live.forEach(::add)
    }
}

/** An entry's slot in the high half and the shift between query and entry, as an unsigned word, in the low half. */
private fun voteKey(slot: Int, shift: Int): Long = (slot.toLong() shl Int.SIZE_BITS) or shift.toUInt().toLong()

/** With [entry] shifted [shift] points against [query], the longest run that passes the bounds. */
private fun longestRun(query: IntArray, entry: IntArray, shift: Int, minPoints: Int, skip: BooleanArray?): IntRange? {
    val finder = RunFinder(minPoints)
    for (i in maxOf(0, -shift) until minOf(query.size, entry.size - shift)) {
        val agrees = Integer.bitCount(query[i] xor entry[i + shift]) <= BreakLibrary.MAX_BIT_ERRORS
        if (skip?.get(i) != true && agrees) finder.agree(i)
    }
    return finder.result()
}

/**
 * The longest run of agreeing points, in order, of [minPoints] or more, with [BreakLibrary.MIN_DENSITY] of them
 * agreeing and no gap over [BreakLibrary.MAX_GAP_POINTS].
 */
private class RunFinder(private val minPoints: Int) {
    private var best: IntRange? = null
    private var start = -1
    private var last = -1
    private var matches = 0

    fun agree(i: Int) {
        if (start >= 0 && i - last > BreakLibrary.MAX_GAP_POINTS) close()
        if (start < 0) {
            start = i
            matches = 0
        }
        last = i
        matches++
    }

    fun result(): IntRange? {
        close()
        return best
    }

    private fun close() {
        if (start < 0) return
        val length = last - start + 1
        val longest = best?.let { length > it.count() } ?: true
        if (length >= minPoints && matches >= BreakLibrary.MIN_DENSITY * length && longest) best = start..last
        start = -1
    }
}
