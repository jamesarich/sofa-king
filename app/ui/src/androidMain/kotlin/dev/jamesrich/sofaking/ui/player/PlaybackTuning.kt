package dev.jamesrich.sofaking.ui.player

import kotlin.time.Duration.Companion.milliseconds
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import dev.jamesrich.sofaking.data.BufferPreference
import kotlin.concurrent.Volatile

/**
 * The buffer and decoder choices as the playback thread reads them: the codec selector and the load control are
 * built once with the player, so a change lands here instead of rebuilding it. [preferSoftware] is set per
 * stream from the channel's own decoder preference before its item is prepared.
 */
class PlaybackTuning {
    @Volatile
    var startBufferMs: Int = BufferPreference.STANDARD.startMs

    @Volatile
    var rebufferMs: Int = BufferPreference.STANDARD.rebufferMs

    @Volatile
    var preferSoftware: Boolean = false

    fun setBuffer(buffer: BufferPreference) {
        startBufferMs = buffer.startMs
        rebufferMs = buffer.rebufferMs
    }

    /** How much must be buffered before playback starts, on a fresh start or after a stall. */
    fun bufferNeededUs(rebuffering: Boolean): Long =
        (if (rebuffering) rebufferMs else startBufferMs).milliseconds.inWholeMicroseconds

    /** Media3's own decoder order, or the software decoders first for the stream a hardware decoder cannot play. */
    @UnstableApi
    val codecSelector: MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val selector = if (preferSoftware) MediaCodecSelector.PREFER_SOFTWARE else MediaCodecSelector.DEFAULT
            selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        }
}
