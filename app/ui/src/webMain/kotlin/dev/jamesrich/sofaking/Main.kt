package dev.jamesrich.sofaking

import androidx.compose.ui.window.ComposeViewport
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.inBrowser
import dev.jamesrich.sofaking.ui.browserFullScreen
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/** Sofa King in a browser, Wasm or JS alike; the page must be served from where the Sofa King server allows (CORS). */
fun main() {
    initSofaKing(AppStorage.inBrowser())
    val fullScreen = browserFullScreen()
    val body = document.body!!
    ComposeViewport(body) { App(windowFullScreen = fullScreen) }
    // The page opens with its body focused, which passes Compose no keys: the arrow keys did nothing until a click.
    focusCanvas(body, framesLeft = 60)
}

/** Focuses the canvas Compose draws in, inside a shadow root of [container], once it is there. */
private fun focusCanvas(container: Element, framesLeft: Int) {
    val nodes = container.querySelectorAll("*")
    for (index in 0 until nodes.length) {
        val canvas = (nodes.item(index) as? Element)?.shadowRoot?.querySelector("canvas") as? HTMLElement ?: continue
        canvas.focus()
        return
    }
    if (framesLeft > 0) window.requestAnimationFrame { focusCanvas(container, framesLeft - 1) }
}
