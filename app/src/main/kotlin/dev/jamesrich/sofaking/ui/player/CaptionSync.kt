package dev.jamesrich.sofaking.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Live captions are typed after the words are spoken, so their timestamps trail the audio. Rendering
 * text [advanceUs] ahead of the playback position pulls them back in line; the cues must already be
 * downloaded, so playback waits for that much buffer before it starts.
 */
class CaptionSync {
    /** The user's chosen advance; playback waits for this much buffer plus [MARGIN_US] before it starts. */
    @Volatile
    var advanceUs: Long = 0L

    /** What the text renderer actually uses; never more than is downloaded, see [nextEffectiveUs]. */
    @Volatile
    var effectiveUs: Long = 0L

    companion object {
        /**
         * Dispatcharr delivers live TS in bursts about 2 s apart, so the buffer swings by ~2 s; starting
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

@UnstableApi
class CaptionSyncRenderersFactory(context: Context, private val sync: CaptionSync) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
        built.mapTo(out) { renderer ->
            object : ForwardingRenderer(renderer) {
                override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                    super.render(positionUs + sync.effectiveUs, elapsedRealtimeUs)
                }
            }
        }
    }
}

/**
 * Forwards every PlayerId/Parameters overload explicitly: Kotlin `by` delegation skips Java default
 * methods, and LoadControl's defaults throw "not implemented".
 */
@UnstableApi
class CaptionSyncLoadControl(
    private val sync: CaptionSync,
    private val delegate: LoadControl = DefaultLoadControl(),
) : LoadControl {
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters) &&
            parameters.bufferedDurationUs >= CaptionSync.bufferNeededToStartUs(sync.advanceUs)
}
