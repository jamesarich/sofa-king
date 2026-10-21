package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.w3c.dom.events.Event

/**
 * The page's full screen through the Fullscreen API, or null where the browser does not offer it for a page (an
 * iPhone's Safari). The browser asks for a click or key press first, which the players' button and F are, and
 * leaves on its own Escape, which [BrowserFullScreen] follows.
 */
internal fun browserFullScreen(): WindowFullScreen? = if (fullscreenEnabled()) BrowserFullScreen() else null

private class BrowserFullScreen : WindowFullScreen {
    override var isFullScreen by mutableStateOf(fullscreenActive())
        private set

    init {
        document.addEventListener("fullscreenchange", { _: Event -> isFullScreen = fullscreenActive() })
    }

    override fun toggle() = if (isFullScreen) exitFullscreen() else requestFullscreen()
}

private fun fullscreenEnabled(): Boolean = js("document.fullscreenEnabled === true")

private fun fullscreenActive(): Boolean = js("document.fullscreenElement != null")

private fun requestFullscreen() {
    js("document.documentElement.requestFullscreen().catch(function () {});")
}

private fun exitFullscreen() {
    js("document.exitFullscreen().catch(function () {});")
}
