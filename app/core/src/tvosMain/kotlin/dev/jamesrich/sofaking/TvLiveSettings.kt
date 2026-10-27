package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.LiveBreaks

/** Settings › Live TV: the guide's preview, captions and what covers ads. */
interface TvLiveSettings {
    /** How long focus rests on a guide channel before its preview opens full screen, or never. */
    fun setAutoOpenDelay(delay: AutoOpenDelay)

    /** Whether a resting guide preview turns its sound on. */
    fun setPreviewSound(enabled: Boolean)

    /** Whether live channels and recordings show their closed captions. */
    fun setCaptionsEnabled(enabled: Boolean)

    /** Whether the break screen covers the ads a streaming channel marks, and whether their sound plays under it. */
    fun setLiveBreaks(mode: LiveBreaks)
}

internal class TvLiveSettingCalls(parts: TvParts) : TvLiveSettings {
    private val viewModel = parts.viewModel

    override fun setAutoOpenDelay(delay: AutoOpenDelay) = viewModel.setAutoOpenDelay(delay)

    override fun setPreviewSound(enabled: Boolean) = viewModel.setPreviewSound(enabled)

    override fun setCaptionsEnabled(enabled: Boolean) = viewModel.setCaptionsEnabled(enabled)

    override fun setLiveBreaks(mode: LiveBreaks) = viewModel.setLiveBreaks(mode)
}
