package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.time.Instant

private const val MINUTE_MS = 60_000L

/** Milliseconds from [epochMs] to the start of the next wall-clock minute; a full minute when it is on one. */
internal fun msToNextMinute(epochMs: Long): Long = MINUTE_MS - Math.floorMod(epochMs, MINUTE_MS)

/**
 * The current instant, refreshed on every wall-clock minute, so the guide and the player turn over together
 * and with the programmes' start times; reading it in composition never calls the clock.
 */
@Composable
internal fun rememberMinuteNow(): State<Instant> {
    val initial = remember { Instant.now() }
    return produceState(initial) {
        while (true) {
            delay(msToNextMinute(System.currentTimeMillis()))
            value = Instant.now()
        }
    }
}
