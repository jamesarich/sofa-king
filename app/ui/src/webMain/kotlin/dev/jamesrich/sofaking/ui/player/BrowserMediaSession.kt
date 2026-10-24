package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import kotlinx.browser.document
import org.w3c.dom.get

/**
 * The page title and the browser's Media Session for [player]: its metadata reaches the OS media controls, and their
 * media keys come back here, next and previous zapping through [onZapStep]. Browsers without the API keep the title
 * alone, which reads [appName] between programs.
 */
internal fun openBrowserSession(appName: String, player: LivePlayer, onZapStep: (Int) -> Unit): MediaSessionHandle {
    setMediaAction("play") { player.play() }
    setMediaAction("pause") { player.pause() }
    setMediaAction("nexttrack") { onZapStep(1) }
    setMediaAction("previoustrack") { onZapStep(-1) }
    return object : MediaSessionHandle {
        override fun publish(nowPlaying: NowPlaying) {
            document.title = "${nowPlaying.title} - $appName"
            setMediaMetadata(nowPlaying.title, nowPlaying.subtitle, nowPlaying.artworkUrl)
        }

        override fun close() {
            document.title = appName
            for (action in MEDIA_ACTIONS) clearMediaAction(action)
            clearMediaMetadata()
        }
    }
}

private val MEDIA_ACTIONS = listOf("play", "pause", "nexttrack", "previoustrack")

/** An action the browser doesn't know throws; it is skipped, as the spec advises. */
// detekt sees no use of the parameters: the JavaScript in js() reads them by name.
@Suppress("UnusedParameter")
private fun setMediaAction(action: String, handler: () -> Unit) {
    js(
        "if ('mediaSession' in navigator) { " +
            "try { navigator.mediaSession.setActionHandler(action, function () { handler(); }); } catch (e) {} }",
    )
}

// detekt sees no use of the parameters: the JavaScript in js() reads them by name.
@Suppress("UnusedParameter")
private fun clearMediaAction(action: String) {
    js(
        "if ('mediaSession' in navigator) { " +
            "try { navigator.mediaSession.setActionHandler(action, null); } catch (e) {} }",
    )
}

// detekt sees no use of the parameters: the JavaScript in js() reads them by name.
@Suppress("UnusedParameter")
private fun setMediaMetadata(title: String, artist: String, artworkUrl: String?) {
    js(
        "if ('mediaSession' in navigator) { navigator.mediaSession.metadata = " +
            "new MediaMetadata({ title: title, artist: artist, artwork: artworkUrl ? [{ src: artworkUrl }] : [] }); }",
    )
}

private fun clearMediaMetadata() {
    js("if ('mediaSession' in navigator) { navigator.mediaSession.metadata = null; }")
}
