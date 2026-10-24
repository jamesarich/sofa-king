package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemAccessLogEvent
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.accessLog
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.selectedMediaOptionInMediaSelectionGroup

/**
 * The closed captions and audio tracks of [av]'s item, in the viewer's iOS caption style. Both are read once the item
 * is ready to play: the asset has no group to report before then, and asking for it blocks the main thread while it
 * loads.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvTracks(private val av: AVPlayer) : PlayerTracks {
    private var captionsOn = false

    // The item whose caption track was chosen, and its legible group.
    private var captionsAppliedTo: AVPlayerItem? = null
    private var legibleGroup: AVMediaSelectionGroup? = null

    override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList<CaptionCue>()).asStateFlow()
    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    override val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    override val hasTextTrack: Boolean
        get() = av.currentItem?.takeIf { it === captionsAppliedTo } != null &&
            legibleGroup?.options?.isNotEmpty() == true

    override fun setCaptionsEnabled(enabled: Boolean) {
        captionsOn = enabled
        apply()
    }

    override fun selectAudioTrack(id: String) {
        val item = av.currentItem ?: return
        val group = audibleGroup(item) ?: return
        val option = id.toIntOrNull()?.let { group.options.getOrNull(it) } as? AVMediaSelectionOption ?: return
        item.selectMediaOption(option, group)
        read(item)
    }

    /**
     * AVFoundation reports the picture size and the stream's indicated bitrate; the codec and frame rate stay unknown.
     */
    override fun streamInfo(): StreamInfo {
        val item = av.currentItem ?: return StreamInfo()
        val (width, height) = item.presentationSize.useContents { width.toInt() to height.toInt() }
        val bitrate = (
            item.accessLog()?.events?.lastOrNull() as? AVPlayerItemAccessLogEvent
            )?.indicatedBitrate?.takeIf {
            it > 0
        }?.toInt()
        return StreamInfo(width = width.takeIf { it > 0 }, height = height.takeIf { it > 0 }, bitrateBps = bitrate)
    }

    private fun audibleGroup(item: AVPlayerItem): AVMediaSelectionGroup? =
        item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible)

    /** The item's audible options, numbered by position; one option is no choice. */
    private fun read(item: AVPlayerItem) {
        val group = audibleGroup(item)
        val options = group?.options.orEmpty().filterIsInstance<AVMediaSelectionOption>()
        val selected = group?.let { item.selectedMediaOptionInMediaSelectionGroup(it) }
        _audioTracks.value =
            if (options.size > 1) {
                options.mapIndexed { index, option ->
                    AudioTrack(
                        index.toString(),
                        option.displayName,
                        option == selected,
                    )
                }
            } else {
                emptyList()
            }
    }

    /** Applies the captions and reads the audio tracks the first time [item] is seen ready to play. */
    fun ready(item: AVPlayerItem) {
        if (item.status != AVPlayerItemStatusReadyToPlay || captionsAppliedTo === item) return
        captionsAppliedTo = item
        legibleGroup = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible)
        apply()
        read(item)
    }

    /** No item: no audio choice. */
    fun clear() {
        _audioTracks.value = emptyList()
    }

    private fun apply() {
        val item = av.currentItem?.takeIf { it === captionsAppliedTo } ?: return
        val group = legibleGroup ?: return
        val option = if (captionsOn) group.options.firstOrNull() as? AVMediaSelectionOption else null
        item.selectMediaOption(option, group)
    }
}
