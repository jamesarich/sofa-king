package dev.jamesrich.sofaking.ui.player

import kotlin.concurrent.Volatile

/**
 * Live captions are typed after the words are spoken, so their timestamps trail the audio. Rendering
 * text [advanceUs] ahead of the playback position pulls them back in line; the cues must already be
 * downloaded, so playback waits for that much buffer before it starts. Players whose engine exposes no
 * text renderer hook (every one but Media3) show captions at their own timing.
 */
class CaptionSync {
    /** The user's chosen advance; playback waits for this much buffer plus [MARGIN_US] before it starts. */
    @Volatile
    var advanceUs: Long = 0L

    /** What the text renderer actually uses; never more than is downloaded, see [nextEffectiveUs]. */
    @Volatile
    var effectiveUs: Long = 0L

    /** Read on every caption update, so a settings change applies without rebuilding the player. */
    @Volatile
    var smoothingEnabled: Boolean = true

    companion object {
        /**
         * Live TS relays deliver in bursts about 2 s apart, so the buffer swings by ~2 s; starting
         * with this much beyond the advance keeps the look-ahead inside downloaded data most of the time.
         */
        const val MARGIN_US = 2_500_000L

        /** Keep the look-ahead this far short of the download edge. */
        const val HEADROOM_US = 750_000L

        /** How fast the look-ahead may grow back after a dip, per update. */
        const val RECOVER_STEP_US = 100_000L

        fun bufferNeededToStartUs(advanceUs: Long): Long = if (advanceUs <= 0L) 0L else advanceUs + MARGIN_US

        /**
         * Shrinks at once when the buffer can't cover the advance (late cues are otherwise dropped or
         * shown in lumps) and grows back in small steps so captions don't jump forward.
         */
        fun nextEffectiveUs(currentUs: Long, advanceUs: Long, bufferedUs: Long): Long {
            val ceiling = minOf(advanceUs, bufferedUs - HEADROOM_US).coerceAtLeast(0L)
            return if (ceiling <= currentUs) ceiling else minOf(ceiling, currentUs + RECOVER_STEP_US)
        }
    }
}
