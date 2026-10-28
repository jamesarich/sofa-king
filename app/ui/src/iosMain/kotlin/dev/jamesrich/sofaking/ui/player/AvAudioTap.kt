package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.asset
import platform.AVFoundation.audioMix
import platform.AVFoundation.loadTracksWithMediaType
import platform.CoreAudioTypes.AudioBuffer
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsNonInterleaved
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreMedia.CMItemCount
import platform.CoreMedia.CMItemCountVar
import platform.MediaToolbox.MTAudioProcessingTapCallbacks
import platform.MediaToolbox.MTAudioProcessingTapCreate
import platform.MediaToolbox.MTAudioProcessingTapFlags
import platform.MediaToolbox.MTAudioProcessingTapFlagsVar
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapGetStorage
import platform.MediaToolbox.MTAudioProcessingTapRef
import platform.MediaToolbox.MTAudioProcessingTapRefVar
import platform.MediaToolbox.kMTAudioProcessingTapCallbacksVersion_0
import platform.MediaToolbox.kMTAudioProcessingTapCreationFlag_PostEffects
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.min

/**
 * Feeds [analyzer] from what an AVPlayerItem plays: an audio mix with an `MTAudioProcessingTap` on the item's audio
 * track hands every block of decoded PCM over before it reaches the speakers. It needs the item's audio track, which
 * a file has and an HLS stream does not (AVFoundation does not run a tap on HLS), so a song the server transcodes
 * into HLS stays blank. The tap runs only while a visualizer draws.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvAudioTap {
    val analyzer = AudioAnalyzer().also { it.onWanted = ::want }

    private var item: AVPlayerItem? = null
    private var wanted = false

    /** Follows [item], the one the player holds now; main thread. */
    fun follow(item: AVPlayerItem?) {
        this.item = item
        update()
    }

    private fun want(on: Boolean) {
        wanted = on
        update()
    }

    private fun update() {
        val item = item ?: return
        if (!wanted) {
            item.audioMix = null
            return
        }
        if (item.audioMix != null) return
        item.asset.loadTracksWithMediaType(AVMediaTypeAudio) { tracks, _ ->
            val track = tracks?.firstOrNull() as? AVAssetTrack ?: return@loadTracksWithMediaType
            dispatch_async(dispatch_get_main_queue()) {
                if (this.item === item && wanted && item.audioMix == null) item.audioMix = mixOf(track)
            }
        }
    }

    private fun mixOf(track: AVAssetTrack): AVMutableAudioMix? {
        val tap = createTap(TapState(analyzer)) ?: return null
        val parameters = AVMutableAudioMixInputParameters.audioMixInputParametersWithTrack(track)
        parameters.setAudioTapProcessor(tap)
        // The parameters hold the tap now.
        CFRelease(tap)
        return AVMutableAudioMix().apply { setInputParameters(listOf(parameters)) }
    }
}

/** What the tap's callbacks share: the analyzer and the layout of the blocks they are handed. */
@OptIn(ExperimentalForeignApi::class)
private class TapState(private val analyzer: AudioAnalyzer) {
    private var float = true
    private var planar = true
    private var channels = 2
    private var scratch = FloatArray(0)

    fun prepare(format: AudioStreamBasicDescription) {
        float = format.mFormatFlags and kAudioFormatFlagIsFloat != 0u
        planar = format.mFormatFlags and kAudioFormatFlagIsNonInterleaved != 0u
        channels = format.mChannelsPerFrame.toInt().coerceAtLeast(1)
        analyzer.sampleRate = format.mSampleRate.toInt().takeIf { it > 0 } ?: analyzer.sampleRate
    }

    fun push(list: AudioBufferList, frames: Int) {
        val buffers = list.mNumberBuffers.toInt()
        if (frames <= 0 || buffers == 0) return
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        if (planar) {
            for (channel in 0 until min(
                2,
                buffers,
            )) {
                copy(list.mBuffers[channel], frames, channel, stride = 1, offset = 0)
            }
            if (buffers == 1) copy(list.mBuffers[0], frames, 1, stride = 1, offset = 0)
        } else {
            val stride = channels
            copy(list.mBuffers[0], frames, 0, stride, 0)
            copy(list.mBuffers[0], frames, 1, stride, if (stride > 1) 1 else 0)
        }
        analyzer.push(scratch, frames, 2)
    }

    /** Puts [buffer]'s samples, every [stride]th from [offset], into [slot] (0 left, 1 right) of the scratch pairs. */
    private fun copy(buffer: AudioBuffer, frames: Int, slot: Int, stride: Int, offset: Int) {
        val data = buffer.mData ?: return
        val bytes = buffer.mDataByteSize.toInt()
        if (float) {
            val samples = data.reinterpret<kotlinx.cinterop.FloatVar>()
            val count = min(frames, bytes / Float.SIZE_BYTES / stride)
            for (i in 0 until count) scratch[i * 2 + slot] = samples[i * stride + offset]
        } else {
            val samples = data.reinterpret<kotlinx.cinterop.ShortVar>()
            val count = min(frames, bytes / Short.SIZE_BYTES / stride)
            for (i in 0 until count) scratch[i * 2 + slot] = samples[i * stride + offset] / PCM16_SCALE
        }
    }

    private companion object {
        const val PCM16_SCALE = 32768f
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createTap(state: TapState): MTAudioProcessingTapRef? = memScoped {
    val callbacks = alloc<MTAudioProcessingTapCallbacks>()
    val ref = StableRef.create(state)
    callbacks.version = kMTAudioProcessingTapCallbacksVersion_0
    callbacks.clientInfo = ref.asCPointer()
    callbacks.`init` = staticCFunction(::tapInit)
    callbacks.finalize = staticCFunction(::tapFinalize)
    callbacks.prepare = staticCFunction(::tapPrepare)
    callbacks.unprepare = staticCFunction(::tapUnprepare)
    callbacks.process = staticCFunction(::tapProcess)
    val out = alloc<MTAudioProcessingTapRefVar>()
    val status = MTAudioProcessingTapCreate(
        kCFAllocatorDefault,
        callbacks.ptr,
        kMTAudioProcessingTapCreationFlag_PostEffects,
        out.ptr,
    )
    if (status == 0) {
        out.value
    } else {
        ref.dispose()
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun stateOf(tap: MTAudioProcessingTapRef?): TapState? =
    MTAudioProcessingTapGetStorage(tap)?.asStableRef<TapState>()?.get()

@OptIn(ExperimentalForeignApi::class)
@Suppress("UnusedParameter") // The C callback's own signature.
private fun tapInit(
    tap: MTAudioProcessingTapRef?,
    clientInfo: COpaquePointer?,
    storageOut: CPointer<COpaquePointerVar>?,
) {
    storageOut?.pointed?.value = clientInfo
}

@OptIn(ExperimentalForeignApi::class)
private fun tapFinalize(tap: MTAudioProcessingTapRef?) {
    MTAudioProcessingTapGetStorage(tap)?.asStableRef<TapState>()?.dispose()
}

@OptIn(ExperimentalForeignApi::class)
@Suppress("UnusedParameter") // The C callback's own signature.
private fun tapPrepare(
    tap: MTAudioProcessingTapRef?,
    maxFrames: CMItemCount,
    format: CPointer<AudioStreamBasicDescription>?,
) {
    val description = format?.pointed ?: return
    stateOf(tap)?.prepare(description)
}

@OptIn(ExperimentalForeignApi::class)
@Suppress("UnusedParameter") // The C callback's own signature.
private fun tapUnprepare(tap: MTAudioProcessingTapRef?) = Unit

@OptIn(ExperimentalForeignApi::class)
@Suppress("LongParameterList", "UnusedParameter") // The C callback's own signature.
private fun tapProcess(
    tap: MTAudioProcessingTapRef?,
    frames: CMItemCount,
    flags: MTAudioProcessingTapFlags,
    buffers: CPointer<AudioBufferList>?,
    framesOut: CPointer<CMItemCountVar>?,
    flagsOut: CPointer<MTAudioProcessingTapFlagsVar>?,
) {
    // The block passes on to the speakers as it is; the tap only reads what the source filled in.
    if (MTAudioProcessingTapGetSourceAudio(tap, frames, buffers, flagsOut, null, framesOut) != 0) return
    val list = buffers?.pointed ?: return
    stateOf(tap)?.push(list, (framesOut?.pointed?.value ?: frames).toInt())
}
