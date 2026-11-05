package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.LiveBreaks
import kotlinx.coroutines.promise
import kotlin.js.Promise

/** The settings and the server, as the page's Settings changes them. */
@JsExport
class WebSettings internal constructor(parts: WebParts) {
    private val viewModel = parts.viewModel
    private val scope = parts.scope

    fun setCaptions(enabled: Boolean) = viewModel.setCaptionsEnabled(enabled)

    /** What the live player does in an ad break: `PLAY`, `SCREEN_MUTED` or `SCREEN`. */
    fun setLiveBreaks(mode: String) {
        LiveBreaks.entries.firstOrNull { it.name == mode }?.let(viewModel::setLiveBreaks)
    }

    /** Saves the server address and its API token; a null [apiToken] keeps the one saved. */
    fun saveServer(url: String, apiToken: String?) =
        viewModel.saveServer(url, apiToken ?: viewModel.settings.value?.apiToken.orEmpty())

    /** Claims a pairing [code] at [url] and saves the server it opens; null on success, else why it failed. */
    fun pair(url: String, code: String): Promise<String?> = scope.promise { viewModel.pairAndWait(url, code)?.name }
}
