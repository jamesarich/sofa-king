package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/** How often the caption lead follows the buffer, and how many of those steps pass between its debug lines. */
private val CAPTION_LEAD_TICK = 250.milliseconds
private const val CAPTION_LOG_TICKS = 8
private val log = Logger.withTag("SofaKing")

/**
 * Sets the captions' look-ahead to [leadUs], and keeps it inside what's downloaded; see [CaptionSync.nextEffectiveUs].
 * Nothing runs with no lead: captions are off, the channel has no advance or the engine cannot apply one.
 * [smoothing] turns the captions' smoothing on.
 */
@Composable
internal fun CaptionLeadEffect(player: LivePlayer, leadUs: Long, smoothing: Boolean) {
    SideEffect {
        player.captionSync.advanceUs = leadUs
        player.captionSync.smoothingEnabled = smoothing
    }
    val active = leadUs != 0L
    LaunchedEffect(player, active) {
        val captionSync = player.captionSync
        if (!active) {
            captionSync.effectiveUs = 0L
            return@LaunchedEffect
        }
        var tick = 0
        while (true) {
            delay(CAPTION_LEAD_TICK)
            // While (re)buffering the start gate guards the lead; clamping then would collapse it to zero.
            if (!player.status.value.isPlaying) continue
            captionSync.effectiveUs = CaptionSync.nextEffectiveUs(
                currentUs = captionSync.effectiveUs,
                advanceUs = captionSync.advanceUs,
                bufferedUs = player.bufferedMs.milliseconds.inWholeMicroseconds,
            )
            if (++tick % CAPTION_LOG_TICKS == 0) {
                log.d {
                    "captions: buffered=${player.bufferedMs}ms lead=${captionSync.effectiveUs.microseconds} of " +
                        "${captionSync.advanceUs.microseconds}"
                }
            }
        }
    }
}
