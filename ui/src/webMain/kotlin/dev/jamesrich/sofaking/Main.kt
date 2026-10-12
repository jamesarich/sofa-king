package dev.jamesrich.sofaking

import androidx.compose.ui.window.ComposeViewport
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.inBrowser
import kotlinx.browser.document

/** Sofa King in a browser, Wasm or JS alike; the page must be served from where the Sofa King server allows (CORS). */
fun main() {
    initSofaKing(AppStorage.inBrowser())
    ComposeViewport(document.body!!) { App() }
}
