package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.SessionRelease
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlin.js.Promise

/** The live channel's session, as the page's player holds it. */
@JsExport
class WebChannels internal constructor(private val parts: WebParts) {
    private val viewModel = parts.viewModel

    /** What the server offers beyond live TV; the Library link shows when it serves libraries. */
    fun watchCapabilities(onChange: (WebCapabilities) -> Unit): Watch =
        parts.watch(viewModel.capabilities.map { it.toWeb() }, onChange)

    /** Streams the server replaced under the held session; the player loads the new URL. */
    fun watchReplaced(onChange: (WebStream) -> Unit): Watch =
        parts.watch(viewModel.sessions.replaced.map { it.toWeb() }, onChange)

    /** Opens, or re-reads, a watch session for [channelId] and remembers it as the last channel. */
    fun watch(channelId: String): Promise<WebStream> = parts.scope.promise {
        parts.opening {
            val stream = viewModel.sessions.tune(channelId, PlaybackIntent.WATCH)
            viewModel.setLastChannel(channelId)
            stream.toWeb()
        }
    }

    /** Releases the held session for good, as leaving the player does; an open still running is canceled first. */
    fun stopWatching() {
        val pending = parts.open
        parts.open = null
        parts.scope.launch {
            pending?.cancelAndJoin()
            viewModel.sessions.release(forget = true)
        }
    }

    /** Opens a fresh session for what was released last, or null when there is nothing to return to. */
    fun reopen(): Promise<WebStream?> = parts.scope.promise {
        parts.opening { viewModel.sessions.reopen()?.toWeb() }
    }

    /**
     * The DELETE that frees the held session, which the page sends with `fetch` and `keepalive` on `pagehide`, since
     * a closing page cannot wait for [stopWatching]; null when nothing is held.
     */
    fun exitRelease(): WebRelease? = viewModel.sessions.exitRelease()?.toWeb()
}

@JsExport
class WebRelease(val url: String, val token: String)

private fun SessionRelease.toWeb() = WebRelease(url, token)

/** The lineup and what changes it, as the guide shows them. */
@JsExport
class WebGuide internal constructor(private val parts: WebParts) {
    private val viewModel = parts.viewModel

    fun watchLineup(onChange: (WebLineup) -> Unit): Watch = parts.watch(viewModel.state.map(::lineupToWeb), onChange)

    fun watchSettings(onChange: (WebSettingsState) -> Unit): Watch =
        parts.watch(viewModel.settings.filterNotNull().map { it.toWeb() }, onChange)

    fun refresh() = viewModel.refresh()

    fun toggleFavorite(channelId: String) = viewModel.toggleFavorite(channelId)

    /** Remembers the chip the guide shows, or null for All. */
    fun setFilter(key: String?) = viewModel.setGuideFilter(key)
}
