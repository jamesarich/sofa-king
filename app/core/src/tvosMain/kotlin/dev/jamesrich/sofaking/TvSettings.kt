package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.TopLevelTab

/** The settings and the server, as the Apple TV app's Settings and guide change them. */
interface TvSettings {
    /** Whether recordings jump over the commercial breaks the server found. */
    fun setSkipCommercials(enabled: Boolean)

    /** Whether an episode's intro skips itself after a countdown on Skip intro. */
    fun setAutoSkipIntros(enabled: Boolean)

    /** Whether an episode not started yet keeps its overview back and its still blurred. */
    fun setHideSpoilers(enabled: Boolean)

    /** Whether the break screen covers the ads a streaming channel marks, and whether their sound plays under it. */
    fun setLiveBreaks(mode: LiveBreaks)

    /**
     * Counts [ms] more of ads the break screen covered on [day], a local date as `2026-10-22`, toward Ads dodged today.
     */
    fun addAdsDodged(day: String, ms: Long)

    /** Remembers [tab] as the one the app was last on, which the next start reopens. */
    fun setLastTab(tab: TopLevelTab)

    /** Claims a pairing [code] at [url] and saves the server it opens; null on success, else why it failed. */
    suspend fun pair(url: String, code: String): PairingFailure?

    fun saveServer(url: String, apiToken: String)

    fun refresh()

    fun toggleFavorite(guideId: String)
}

internal class TvSettingCalls(parts: TvParts) : TvSettings {
    private val viewModel = parts.viewModel

    override fun setSkipCommercials(enabled: Boolean) = viewModel.setSkipCommercials(enabled)

    override fun setAutoSkipIntros(enabled: Boolean) = viewModel.setAutoSkipIntros(enabled)

    override fun setHideSpoilers(enabled: Boolean) = viewModel.setHideSpoilers(enabled)

    override fun setLiveBreaks(mode: LiveBreaks) = viewModel.setLiveBreaks(mode)

    override fun addAdsDodged(day: String, ms: Long) = viewModel.addAdsDodged(day, ms)

    override fun setLastTab(tab: TopLevelTab) = viewModel.setLastTab(tab)

    override suspend fun pair(url: String, code: String): PairingFailure? = viewModel.pairAndWait(url, code)

    override fun saveServer(url: String, apiToken: String) = viewModel.saveServer(url, apiToken)

    override fun refresh() = viewModel.refresh()

    override fun toggleFavorite(guideId: String) = viewModel.toggleFavorite(guideId)
}
