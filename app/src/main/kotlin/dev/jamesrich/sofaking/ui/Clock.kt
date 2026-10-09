package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.time.Instant

private const val MINUTE_MS = 60_000L

/** The current instant, refreshed once a minute; reading it in composition never calls the clock. */
@Composable
internal fun rememberMinuteNow(): State<Instant> {
    val initial = remember { Instant.now() }
    return produceState(initial) {
        while (true) {
            delay(MINUTE_MS)
            value = Instant.now()
        }
    }
}
