package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.MINUTES_PER_HOUR
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/** One stretch of a virtual channel's schedule: an item, filler, or a blank picture when [itemId] is null. */
data class Airing(
    val startsAt: Instant,
    val endsAt: Instant,
    val itemId: String?,
    /** The position of the slot it aired for; null for filler and blank. */
    val slot: Int? = null,
    val filler: Boolean = false,
)

/** An item a slot or the filler can air, and how long it runs. */
class PoolItem(val id: String, val durationMs: Long)

/**
 * The breaks a channel airs after an item once [everyMs] have passed since the last: one of [items] in turn, shuffled,
 * then each of [slides], airing IDs from [slideAiringId], for [slideMs] each.
 */
class BreakPlan(val everyMs: Long, val items: List<PoolItem>, val slides: List<String>, val slideMs: Long)

/**
 * A slot as the schedule reads it: its items in play order, shuffled or not, and its start in minutes of the day. A
 * timed slot with [live], an airing ID from [liveAiringId], airs that live channel for its whole window instead.
 */
class ScheduleSlot(
    val position: Int,
    val items: List<PoolItem>,
    val shuffle: Boolean,
    val startMinute: Int? = null,
    val live: String? = null,
)

/**
 * Extends a virtual channel's schedule. Untimed slots take turns, one item each; a timed slot runs from its start until
 * the next one's, as many whole items as fit, and the first item of a slot always airs even when it runs past the
 * next start, which then starts late. In order, a slot plays the item after the one it aired last; shuffled, an item
 * it has not aired among its last ones. After each item the schedule fills to the next [padMinutes] mark, and before a
 * timed slot's start, with [filler] items that fit, and a blank picture for the rest; [breaks] air first, when they fit
 * before the next timed slot's start. A live slot airs its channel
 * from its start, or from when the slot before it ends, to the next start. [history] is what aired before,
 * oldest first, which the order and the shuffle continue from.
 */
internal class ScheduleBuilder(
    private val slots: List<ScheduleSlot>,
    private val filler: List<PoolItem>,
    private val padMinutes: Int,
    private val zone: ZoneId,
    history: List<Airing>,
    private val random: Random = Random.Default,
    private val breaks: BreakPlan? = null,
) {
    private val breakSlot = breaks?.let { ScheduleSlot(BREAK_SLOT, it.items, shuffle = true) }
    private var lastBreak: Instant? = null
    private val aired = HashMap<Int, ArrayDeque<String>>()
    private var lastSlot: Int? = null
    private var lastSlotStart: Instant? = null
    private var lastFiller: String? = null
    private val timed = slots.filter { it.startMinute != null }.sortedBy { it.startMinute }
    private val out = ArrayList<Airing>()

    init {
        for (airing in history) remember(airing)
    }

    /** The airings from [from] that cover the schedule until at least [until]. */
    fun extend(from: Instant, until: Instant): List<Airing> {
        var at = from
        var window = lastSlotStart?.takeIf { timed.isNotEmpty() }?.let { windowOf(it).start }
        while (at < until) {
            if (timed.isEmpty()) {
                at = nextTurn(at)
            } else {
                val current = windowOf(at)
                at = nextTimed(at, current, first = current.start != window)
                window = current.start
            }
        }
        return out.toList()
    }

    /** Untimed: the next slot in turn airs its next item; with nothing in any slot, a blank half hour. */
    private fun nextTurn(at: Instant): Instant {
        val order = slots.sortedBy { it.position }
        val after = order.indexOfFirst { it.position == lastSlot }
        val turn = (1..order.size).map { order[(after + it).mod(order.size)] }
            .firstNotNullOfOrNull { slot -> next(slot)?.let { slot to it } }
            ?: return blank(at, at.plus(BLANK_BLOCK))
        val (slot, item) = turn
        return afterItem(add(Airing(at, at.plusMillis(item.durationMs), item.id, slot.position)), null)
    }

    /**
     * Timed: the slot of the [current] window airs its next item when it fits before the window ends, or when it is the
     * [first] of the window; otherwise filler and blank take the window to its end.
     */
    private fun nextTimed(at: Instant, current: Window, first: Boolean): Instant {
        val live = current.slot.live
        val item = if (live == null) next(current.slot) else null
        return when {
            live != null -> add(Airing(at, current.end, live, current.slot.position))

            item == null || (!first && at.plusMillis(item.durationMs) > current.end) -> fill(at, current.end)

            else -> afterItem(
                add(Airing(at, at.plusMillis(item.durationMs), item.id, current.slot.position)),
                current.end,
            )
        }
    }

    /** A break when one is due, then filler to the next mark, all before [limit] when there is one; returns the end. */
    private fun afterItem(end: Instant, limit: Instant?): Instant {
        if (limit != null && end >= limit) return end
        val after = airBreak(end, limit)
        val mark = markAfter(after, padMinutes, zone) ?: return after
        return fill(after, if (limit == null) mark else minOf(mark, limit))
    }

    /**
     * The break due at [from], if one is and it fits before [limit]: its item when that fits too, then the slides.
     * Returns where it ends, [from] without one.
     */
    private fun airBreak(from: Instant, limit: Instant?): Instant {
        val plan = breaks ?: return from
        val slot = checkNotNull(breakSlot)
        val since = lastBreak?.let { Duration.between(it, from).toMillis() } ?: Long.MAX_VALUE
        val room = limit?.let { Duration.between(from, it).toMillis() } ?: Long.MAX_VALUE
        val slidesMs = plan.slideMs * plan.slides.size
        val item = next(slot)?.takeIf { it.durationMs + slidesMs <= room }
        val empty = item == null && slidesMs == 0L
        if (since < plan.everyMs || slidesMs > room || empty) return from
        var at = from
        if (item != null) at = add(Airing(at, at.plusMillis(item.durationMs), item.id, BREAK_SLOT, filler = true))
        for (slide in plan.slides) at = add(Airing(at, at.plusMillis(plan.slideMs), slide, BREAK_SLOT, filler = true))
        return at
    }

    private class Window(val slot: ScheduleSlot, val start: Instant, val end: Instant)

    /** The timed slot whose window holds [at]: the last to start at or before it, wrapping to the day before. */
    private fun windowOf(at: Instant): Window {
        val local = at.atZone(zone)
        val today = local.toLocalDate()
        val starts = (-1L..1L).flatMap { day ->
            timed.map { slot -> slot to ZonedDateTime.of(today.plusDays(day), timeOf(slot), zone).toInstant() }
        }
        val index = starts.indexOfLast { it.second <= at }
        val (slot, start) = starts[index]
        return Window(slot, start, starts[index + 1].second)
    }

    /** Filler items that fit between [from] and [to], then a blank picture for the rest; returns [to]. */
    private fun fill(from: Instant, to: Instant): Instant {
        var at = from
        while (Duration.between(at, to).toMillis() >= MIN_BLANK_MS) {
            val left = Duration.between(at, to).toMillis()
            val fits = filler.filter { it.durationMs <= left }
            val pick = (fits.filter { it.id != lastFiller }.ifEmpty { fits }).randomOrNull(random) ?: break
            lastFiller = pick.id
            at = add(Airing(at, at.plusMillis(pick.durationMs), pick.id, filler = true))
        }
        return if (at < to) blank(at, to) else at
    }

    /** A blank picture from [from] to [to]; one shorter than [MIN_BLANK_MS] stretches the airing before it instead. */
    private fun blank(from: Instant, to: Instant): Instant {
        val last = out.lastOrNull()
        if (Duration.between(from, to).toMillis() < MIN_BLANK_MS && last != null && last.endsAt == from) {
            out[out.size - 1] = last.copy(endsAt = to)
            return to
        }
        return add(Airing(from, to, null))
    }

    private fun add(airing: Airing): Instant {
        out += airing
        remember(airing)
        return airing.endsAt
    }

    private fun remember(airing: Airing) {
        val slot = airing.slot ?: return
        val id = airing.itemId ?: return
        if (slot == BREAK_SLOT) {
            lastBreak = airing.endsAt
        } else {
            lastSlot = slot
            lastSlotStart = airing.startsAt
        }
        val list = aired.getOrPut(slot) { ArrayDeque() }
        list.addLast(id)
        if (list.size > HISTORY_LIMIT) list.removeFirst()
    }

    /** The item [slot] airs next, or null when it has none. */
    private fun next(slot: ScheduleSlot): PoolItem? {
        val items = slot.items.ifEmpty { return null }
        val history = aired[slot.position].orEmpty()
        if (!slot.shuffle) {
            val last = history.lastOrNull() ?: return items.first()
            return items[(items.indexOfFirst { it.id == last } + 1).mod(items.size)]
        }
        val recent = history.takeLast(items.size - 1).toSet()
        return items.filter { it.id !in recent }.ifEmpty { items }.random(random)
    }

    companion object {
        /** What one blank airing covers when no slot has anything to air. */
        val BLANK_BLOCK: Duration = Duration.ofMinutes(30)

        /** A gap shorter than this goes to the airing before it, as a blank picture that short is not worth a start. */
        const val MIN_BLANK_MS = 2_000L

        /** The slot position break airings carry, so the order of break items continues across rebuilds. */
        const val BREAK_SLOT = -1

        /** The most items a slot remembers airing, for its order and its shuffle. */
        const val HISTORY_LIMIT = 5_000
    }
}

private fun timeOf(slot: ScheduleSlot): LocalTime {
    val minute = checkNotNull(slot.startMinute)
    return LocalTime.of(minute / MINUTES_PER_HOUR, minute % MINUTES_PER_HOUR)
}

/** The next [padMinutes] mark after [at] in [zone]; null without padding or when [at] is on one. */
private fun markAfter(at: Instant, padMinutes: Int, zone: ZoneId): Instant? {
    if (padMinutes <= 0) return null
    val local = at.atZone(zone)
    val hour = local.truncatedTo(ChronoUnit.HOURS)
    val minutes = Duration.between(hour, local).toMillis()
    val step = padMinutes * MINUTE_MS
    if (minutes % step == 0L) return null
    return hour.plus(Duration.ofMillis((minutes / step + 1) * step)).toInstant()
}

private const val MINUTE_MS = 60_000L
