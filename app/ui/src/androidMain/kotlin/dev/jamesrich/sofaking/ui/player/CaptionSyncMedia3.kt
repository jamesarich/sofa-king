package dev.jamesrich.sofaking.ui.player

import android.app.ActivityManager
import android.content.Context
import android.os.Looper
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import dev.jamesrich.sofaking.data.BufferPreference
import dev.jamesrich.sofaking.visualizer.AudioAnalyzer

@UnstableApi
class CaptionSyncRenderersFactory(
    context: Context,
    private val sync: CaptionSync,
    private val tap: AudioAnalyzer? = null,
) : DefaultRenderersFactory(context) {
    /** The default sink, with the [tap] listening to what it plays. */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val processors = tap?.let { arrayOf<AudioProcessor>(TeeAudioProcessor(AnalyzerSink(it))) } ?: emptyArray()
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setAudioProcessors(processors)
            .build()
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        val smoothed = SmoothingTextOutput(output, outputLooper) { sync.smoothingEnabled }
        super.buildTextRenderers(context, smoothed, outputLooper, extensionRendererMode, built)
        built.mapTo(out) { renderer ->
            object : ForwardingRenderer(renderer) {
                override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                    super.render(positionUs + sync.effectiveUs, elapsedRealtimeUs)
                }
            }
        }
    }
}

/** The largest heap class, in MB, that counts as a low-memory device when the system does not flag it. */
internal const val LOW_RAM_MEMORY_CLASS_MB = 192

/** The buffer cap on a low-memory device: with the player's other allocations a default-sized one risks the heap. */
internal const val LOW_RAM_TARGET_BUFFER_BYTES = 32 * 1024 * 1024

/** Whether the device is short on memory: flagged by the system, or an app heap class of [LOW_RAM_MEMORY_CLASS_MB]. */
internal fun isLowRam(flaggedLowRam: Boolean, memoryClassMb: Int): Boolean =
    flaggedLowRam || memoryClassMb <= LOW_RAM_MEMORY_CLASS_MB

/** [isLowRam] for this device. */
internal fun Context.isLowRamDevice(): Boolean {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return isLowRam(am.isLowRamDevice, am.memoryClass)
}

/**
 * The stock load control with the buffer durations the live player wants. On a [lowRam] device the buffer is also
 * capped in bytes, by size rather than by time, so a high-bitrate stream cannot outgrow the heap; the durations
 * stay the same.
 */
@UnstableApi
internal fun defaultDelegate(lowRam: Boolean): LoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
        BufferPreference.FAST_START.startMs,
        BufferPreference.FAST_START.rebufferMs,
    )
    .apply {
        if (lowRam) {
            setTargetBufferBytes(LOW_RAM_TARGET_BUFFER_BYTES)
            setPrioritizeTimeOverSizeThresholds(false)
        }
    }
    .build()

/**
 * Forwards every PlayerId/Parameters overload explicitly: Kotlin `by` delegation skips Java default
 * methods, and LoadControl's defaults throw "not implemented". Playback starts once the caption lead and the
 * chosen buffer are both downloaded; the delegate is built with the lowest start thresholds [tuning] can ask
 * for, so the tuning decides.
 */
@UnstableApi
class CaptionSyncLoadControl(
    private val sync: CaptionSync,
    private val tuning: PlaybackTuning = PlaybackTuning(),
    private val delegate: LoadControl = defaultDelegate(lowRam = false),
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

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters) &&
            parameters.bufferedDurationUs >= CaptionSync.bufferNeededToStartUs(sync.advanceUs) &&
            parameters.bufferedDurationUs >= tuning.bufferNeededUs(parameters.rebuffering)
}
