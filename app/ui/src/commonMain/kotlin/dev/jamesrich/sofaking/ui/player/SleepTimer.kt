package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The sleep timer's settings, in the order a press on its button steps through them. */
enum class SleepChoice(val minutes: Int?) {
    OFF(null),
    MINUTES_15(15),
    MINUTES_30(30),
    MINUTES_60(60),
    MINUTES_90(90),

    /** The end of the live program on now, or of the library item or recording playing. */
    END(null),
}

/**
 * The players' sleep timer. It lasts through a zap and through Up next, and the app turns it off when the viewer
 * leaves the full-screen player. A number of minutes stops playback at [stopAtMs], on the wall clock;
 * [SleepChoice.END] stops it as the live program on now ends, or as the item playing ends, in place of Up next.
 * Stopping leaves the player, as Back does.
 */
@Stable
class SleepTimer {
    var choice by mutableStateOf(SleepChoice.OFF)
        private set

    /** When playback stops, or null when it waits for the item's end or the timer is off. */
    var stopAtMs by mutableStateOf<Long?>(null)
        private set

    /**
     * One step on from [choice], at [nowMs]. End is offered with a live program's [programEndMs], or for an item
     * with [endOfItem]; otherwise the step after 90 minutes turns the timer off.
     */
    fun step(nowMs: Long, programEndMs: Long? = null, endOfItem: Boolean = false) {
        var next = SleepChoice.entries[(choice.ordinal + 1) % SleepChoice.entries.size]
        val programOver = programEndMs == null || programEndMs <= nowMs
        if (next == SleepChoice.END && programOver && !endOfItem) {
            next = SleepChoice.OFF
        }
        choice = next
        stopAtMs = when {
            next.minutes != null -> nowMs + next.minutes * MINUTE_MS
            next == SleepChoice.END -> programEndMs?.takeIf { !endOfItem }
            else -> null
        }
    }

    fun cancel() {
        choice = SleepChoice.OFF
        stopAtMs = null
    }

    /** Playback should stop at [nowMs]. */
    fun due(nowMs: Long): Boolean = stopAtMs?.let { nowMs >= it } ?: false

    /** The item playing ends playback when it ends, rather than offering Up next. */
    val atEndOfItem: Boolean get() = choice == SleepChoice.END && stopAtMs == null

    /** The minutes left, rounded up, while a time is set. */
    fun minutesLeft(nowMs: Long): Int? =
        stopAtMs?.let { (((it - nowMs).coerceAtLeast(0) + MINUTE_MS - 1) / MINUTE_MS).toInt() }
}

/**
 * Counts the items Up next has played by itself since the viewer last pressed anything, so a household that fell
 * asleep is asked "Still watching?" rather than played a whole season.
 */
class AutoplayRun {
    private var count = 0
    private var lastMs = Long.MIN_VALUE

    /** Whether the Up next card asks first, given the viewer's last key, tap or click at [lastInputMs]. */
    fun askStillWatching(lastInputMs: Long): Boolean {
        if (lastInputMs > lastMs) count = 0
        return count >= STILL_WATCHING_AFTER
    }

    /** Up next played the next item by itself at [nowMs]. */
    fun autoplayed(nowMs: Long) {
        count++
        lastMs = nowMs
    }
}

/** How many items Up next plays by itself in a row before it asks "Still watching?" in place of the next. */
internal const val STILL_WATCHING_AFTER = 2

/** How long "Still watching?" waits for an answer before it stops. */
internal const val STILL_WATCHING_SECONDS = 30

private const val MINUTE_MS = 60_000L
