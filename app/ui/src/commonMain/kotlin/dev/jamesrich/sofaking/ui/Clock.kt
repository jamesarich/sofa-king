package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private const val MINUTE_MS = 60_000L

/** Milliseconds from [epochMs] to the start of the next wall-clock minute; a full minute when it is on one. */
internal fun msToNextMinute(epochMs: Long): Long = MINUTE_MS - epochMs.mod(MINUTE_MS)

/** The calendar day [at] falls on in [zone], as `2026-10-22`: the day Ads dodged today counts in. */
internal fun localDay(at: Instant = Clock.System.now(), zone: TimeZone = TimeZone.currentSystemDefault()): String =
    at.toLocalDateTime(zone).date.toString()

/** The clock the screens tell the time by: the system's, or a fixed one in previews and screenshot tests. */
internal val LocalClock = staticCompositionLocalOf<Clock> { Clock.System }

/**
 * The current instant, refreshed on every wall-clock minute, so the guide and the player turn over together
 * and with the programs' start times; reading it in composition never calls the clock. It is read again each
 * time the app comes to the front, since a delay pending through standby can wake long after its minute.
 */
@Composable
internal fun rememberMinuteNow(): State<Instant> {
    val clock = LocalClock.current
    val initial = remember(clock) { clock.now() }
    val lifecycle = appLifecycleOwner.lifecycle
    return produceState(initial, lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = clock.now()
            while (true) {
                delay(msToNextMinute(clock.now().toEpochMilliseconds()))
                value = clock.now()
            }
        }
    }
}

/**
 * The device's zone, read again on every wall-clock minute and each time [lifecycle] comes to the front, so times
 * follow a zone set while the app runs: a trip, or an emulator whose zone arrives from the network after the app
 * started. The app provides it as [LocalTimeZone]; a zone read once would keep the first for the life of the process.
 */
@Composable
internal fun rememberSystemTimeZone(lifecycle: Lifecycle): State<TimeZone> {
    val initial = remember { TimeZone.currentSystemDefault() }
    return produceState(initial, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = TimeZone.currentSystemDefault()
                delay(msToNextMinute(Clock.System.now().toEpochMilliseconds()))
            }
        }
    }
}
