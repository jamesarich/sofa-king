package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.BufferPreference
import dev.jamesrich.sofaking.data.CaptionSettings
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.ChannelSettings
import dev.jamesrich.sofaking.data.ClockFormat
import dev.jamesrich.sofaking.data.DecoderPreference
import dev.jamesrich.sofaking.data.DisplaySettings
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.PlayerSettings
import dev.jamesrich.sofaking.data.SeekSteps
import dev.jamesrich.sofaking.data.SkipKind
import dev.jamesrich.sofaking.data.SkipMode
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.data.SubtitleLook
import dev.jamesrich.sofaking.data.TopLevelTab
import dev.jamesrich.sofaking.data.TrackChoice
import dev.jamesrich.sofaking.data.logFailures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The screens' writes to the settings, by the same areas as the store's ([ChannelSettings] and the rest). Each is
// saved in the ViewModel's scope, so a screen calls it and moves on; a write the store refuses is logged rather than
// crashing the app.

/** The channels the viewer keeps, as the screens change them. */
interface ChannelEdits {
    fun toggleFavorite(guideId: String)

    fun setLastChannel(guideId: String)

    fun setGuideFilter(key: String?)

    fun setChannelHidden(guideId: String, hidden: Boolean)

    fun setChannelsHidden(guideIds: Collection<String>, hidden: Boolean)

    fun setZapSkipsHidden(enabled: Boolean)

    fun setFavoritesOrder(order: List<String>)

    fun setStartup(target: StartupTarget, guideId: String? = null)

    fun setLastTab(tab: TopLevelTab)
}

/** Captions and subtitles, as the screens change them. */
interface CaptionEdits {
    fun setCaptionsEnabled(enabled: Boolean)

    fun setCaptionAdvanceMs(ms: Int)

    fun setChannelCaptionAdvance(guideId: String, ms: Int)

    fun setSmoothCaptions(enabled: Boolean)

    fun setCaptionStyle(style: CaptionStyle)

    fun setSubtitleLook(look: SubtitleLook)

    /** Keeps the tracks picked in the show [showId] for its next episodes. */
    fun setTrackChoice(showId: String, choice: TrackChoice)
}

/** How the players play, as the screens change it. */
interface PlayerEdits {
    fun setVolume(level: Int, muted: Boolean)

    fun setMatchFrameRate(enabled: Boolean)

    fun setNightListening(enabled: Boolean)

    fun setDecoder(decoder: DecoderPreference)

    fun setChannelDecoder(guideId: String, decoder: DecoderPreference?)

    fun setBuffer(buffer: BufferPreference)

    /** How the players skip [kind]. */
    fun setSkip(kind: SkipKind, mode: SkipMode)

    fun setSeekSteps(steps: SeekSteps)

    fun setLiveBreaks(mode: LiveBreaks)

    /** Counts [ms] more of ads the break screen covered on [day], a local date as `2026-10-22`. */
    fun addAdsDodged(day: String, ms: Long)
}

/** How the app looks around the player, as the screens change it. */
interface DisplayEdits {
    fun setAutoOpenDelay(delay: AutoOpenDelay)

    fun setPreviewSound(enabled: Boolean)

    fun setKeyLegendShown(count: Int)

    fun setClockFormat(format: ClockFormat)

    fun setGuideDensity(density: GuideDensity)

    fun setHideSpoilers(enabled: Boolean)
}

/** Writes a setting in [this] scope; a write the store refuses is logged rather than crashing the app. */
internal fun CoroutineScope.save(write: suspend () -> Unit) {
    launch(logFailures) { write() }
}

internal class StoreChannelEdits(private val store: ChannelSettings, private val scope: CoroutineScope) :
    ChannelEdits {
    override fun toggleFavorite(guideId: String) = scope.save { store.toggleFavorite(guideId) }

    override fun setLastChannel(guideId: String) = scope.save { store.setLastChannel(guideId) }

    override fun setGuideFilter(key: String?) = scope.save { store.setGuideFilter(key) }

    override fun setChannelHidden(guideId: String, hidden: Boolean) =
        scope.save { store.setChannelHidden(guideId, hidden) }

    override fun setChannelsHidden(guideIds: Collection<String>, hidden: Boolean) =
        scope.save { store.setChannelsHidden(guideIds, hidden) }

    override fun setZapSkipsHidden(enabled: Boolean) = scope.save { store.setZapSkipsHidden(enabled) }

    override fun setFavoritesOrder(order: List<String>) = scope.save { store.setFavoritesOrder(order) }

    override fun setStartup(target: StartupTarget, guideId: String?) = scope.save { store.setStartup(target, guideId) }

    override fun setLastTab(tab: TopLevelTab) = scope.save { store.setLastTab(tab) }
}

internal class StoreCaptionEdits(private val store: CaptionSettings, private val scope: CoroutineScope) :
    CaptionEdits {
    override fun setCaptionsEnabled(enabled: Boolean) = scope.save { store.setCaptionsEnabled(enabled) }

    override fun setCaptionAdvanceMs(ms: Int) = scope.save { store.setCaptionAdvanceMs(ms) }

    override fun setChannelCaptionAdvance(guideId: String, ms: Int) =
        scope.save { store.setChannelCaptionAdvance(guideId, ms) }

    override fun setSmoothCaptions(enabled: Boolean) = scope.save { store.setSmoothCaptions(enabled) }

    override fun setCaptionStyle(style: CaptionStyle) = scope.save { store.setCaptionStyle(style) }

    override fun setSubtitleLook(look: SubtitleLook) = scope.save { store.setSubtitleLook(look) }

    override fun setTrackChoice(showId: String, choice: TrackChoice) =
        scope.save { store.setTrackChoice(showId, choice) }
}

internal class StorePlayerEdits(private val store: PlayerSettings, private val scope: CoroutineScope) : PlayerEdits {
    override fun setVolume(level: Int, muted: Boolean) = scope.save { store.setVolume(level, muted) }

    override fun setMatchFrameRate(enabled: Boolean) = scope.save { store.setMatchFrameRate(enabled) }

    override fun setNightListening(enabled: Boolean) = scope.save { store.setNightListening(enabled) }

    override fun setDecoder(decoder: DecoderPreference) = scope.save { store.setDecoder(decoder) }

    override fun setChannelDecoder(guideId: String, decoder: DecoderPreference?) =
        scope.save { store.setChannelDecoder(guideId, decoder) }

    override fun setBuffer(buffer: BufferPreference) = scope.save { store.setBuffer(buffer) }

    override fun setSkip(kind: SkipKind, mode: SkipMode) = scope.save { store.setSkip(kind, mode) }

    override fun setSeekSteps(steps: SeekSteps) = scope.save { store.setSeekSteps(steps) }

    override fun setLiveBreaks(mode: LiveBreaks) = scope.save { store.setLiveBreaks(mode) }

    override fun addAdsDodged(day: String, ms: Long) {
        if (ms > 0) scope.save { store.addAdsDodged(day, ms) }
    }
}

internal class StoreDisplayEdits(private val store: DisplaySettings, private val scope: CoroutineScope) :
    DisplayEdits {
    override fun setAutoOpenDelay(delay: AutoOpenDelay) = scope.save { store.setAutoOpenDelay(delay) }

    override fun setPreviewSound(enabled: Boolean) = scope.save { store.setPreviewSound(enabled) }

    override fun setKeyLegendShown(count: Int) = scope.save { store.setKeyLegendShown(count) }

    override fun setClockFormat(format: ClockFormat) = scope.save { store.setClockFormat(format) }

    override fun setGuideDensity(density: GuideDensity) = scope.save { store.setGuideDensity(density) }

    override fun setHideSpoilers(enabled: Boolean) = scope.save { store.setHideSpoilers(enabled) }
}
