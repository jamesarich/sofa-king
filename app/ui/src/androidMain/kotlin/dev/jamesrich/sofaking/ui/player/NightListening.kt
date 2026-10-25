package dev.jamesrich.sofaking.ui.player

import android.media.audiofx.DynamicsProcessing
import androidx.media3.common.C
import co.touchlab.kermit.Logger

/**
 * Night listening on Android: the platform's [DynamicsProcessing] effect on the player's audio session, one
 * compressor band over the whole spectrum that lifts quiet passages and holds loud ones down, then a limiter. Audio
 * passed through to a receiver never reaches the effect, so it plays as it is.
 */
internal class NightListening {
    private var effect: DynamicsProcessing? = null
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var enabled = false

    /** Turns it on or off for the session the player now uses. */
    fun update(enabled: Boolean, sessionId: Int) {
        if (enabled == this.enabled && sessionId == this.sessionId) return
        this.enabled = enabled
        this.sessionId = sessionId
        release()
        if (enabled && sessionId != C.AUDIO_SESSION_ID_UNSET) effect = open(sessionId)
    }

    fun release() {
        effect?.release()
        effect = null
    }

    private fun open(sessionId: Int): DynamicsProcessing? = runCatching {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            MAX_CHANNELS,
            false,
            0,
            true,
            1,
            false,
            0,
            true,
        ).build()
        config.setMbcBandAllChannelsTo(0, compressor())
        config.setLimiterAllChannelsTo(limiter())
        DynamicsProcessing(0, sessionId, config).apply { setEnabled(true) }
    }.onFailure { e -> log.w(e) { "night listening: the audio effect did not start" } }.getOrNull()

    private fun compressor() = DynamicsProcessing.MbcBand(
        true,
        CUTOFF_HZ,
        ATTACK_MS,
        RELEASE_MS,
        RATIO,
        THRESHOLD_DB,
        KNEE_DB,
        NOISE_GATE_DB,
        EXPANDER_RATIO,
        0f,
        MAKEUP_DB,
    )

    private fun limiter() = DynamicsProcessing.Limiter(true, true, 0, ATTACK_MS, RELEASE_MS, LIMIT_RATIO, LIMIT_DB, 0f)

    private companion object {
        val log = Logger.withTag("SofaKing")

        /** Up to 7.1; the effect covers every channel the session has. */
        const val MAX_CHANNELS = 8
        const val CUTOFF_HZ = 20_000f
        const val ATTACK_MS = 10f
        const val RELEASE_MS = 300f
        const val RATIO = 4f
        const val THRESHOLD_DB = -30f
        const val KNEE_DB = 6f
        const val NOISE_GATE_DB = -90f
        const val EXPANDER_RATIO = 1f
        const val MAKEUP_DB = 10f
        const val LIMIT_RATIO = 10f
        const val LIMIT_DB = -3f
    }
}
