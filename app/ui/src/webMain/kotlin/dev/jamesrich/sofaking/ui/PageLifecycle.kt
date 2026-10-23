package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.jamesrich.sofaking.data.PlaybackSessions
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import org.w3c.dom.events.Event
import kotlin.time.Duration.Companion.milliseconds

/**
 * The window's lifecycle, held to the tab's visibility. Compose moves it on focus and blur as well, so a blur while
 * the tab is hidden would lift it to started; here a hidden tab stays stopped. A tab shown again counts as started
 * once it has stayed visible for [SETTLE]; a stop, and a move between started and resumed, follow at once.
 */
@Composable
internal actual fun rememberAppLifecycleOwner(host: LifecycleOwner): LifecycleOwner {
    val page = remember(host) { PageLifecycleOwner(heldToVisibility(host.lifecycle.currentState, pageVisible())) }
    LaunchedEffect(host, page) {
        val visible = MutableStateFlow(pageVisible())
        val onVisibilityChange: (Event) -> Unit = { visible.value = pageVisible() }
        document.addEventListener("visibilitychange", onVisibilityChange)
        try {
            combine(host.lifecycle.currentStateFlow, visible, ::heldToVisibility).collectLatest { state ->
                if (state.isAtLeast(Lifecycle.State.STARTED) &&
                    !page.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    delay(SETTLE)
                }
                page.lifecycle.currentState = state
            }
        } finally {
            document.removeEventListener("visibilitychange", onVisibilityChange)
        }
    }
    return page
}

/**
 * Sends the held session's release as the page goes (closed, reloaded or navigated away). The app's own release on
 * stop runs a moment later, by which time a closing page is gone; a keepalive request outlives the page.
 */
@Composable
internal actual fun ReleaseOnPageExit(sessions: PlaybackSessions) {
    DisposableEffect(sessions) {
        val onPageHide: (Event) -> Unit = { sessions.exitRelease()?.let { sendRelease(it.url, it.token) } }
        window.addEventListener("pagehide", onPageHide)
        onDispose { window.removeEventListener("pagehide", onPageHide) }
    }
}

private class PageLifecycleOwner(initial: Lifecycle.State) : LifecycleOwner {
    override val lifecycle = LifecycleRegistry(this).apply {
        if (initial != Lifecycle.State.DESTROYED) {
            currentState = initial
        }
    }
}

private fun heldToVisibility(state: Lifecycle.State, visible: Boolean): Lifecycle.State =
    if (visible) state else minOf(state, Lifecycle.State.CREATED)

/** Longer than the flashes seen from a host redrawing a covered page (up to about 180 ms), short beside a tune. */
private val SETTLE = 500.milliseconds

private fun pageVisible(): Boolean = js("document.visibilityState === 'visible'")

/** The answer is not read: a session the server already let go answers 404 or 410, and the lease covers a miss. */
private fun sendRelease(url: String, token: String) {
    js(
        "fetch(url, { method: 'DELETE', keepalive: true, headers: token ? { 'Authorization': 'Bearer ' + token } : " +
            "{} }).catch(function () {});",
    )
}
