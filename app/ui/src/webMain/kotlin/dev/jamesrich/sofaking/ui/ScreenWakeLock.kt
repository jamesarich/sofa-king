package dev.jamesrich.sofaking.ui

import kotlinx.browser.document
import org.w3c.dom.events.Event

/**
 * The Screen Wake Lock API, which keeps the display awake while a screen plays: the picture is drawn in the canvas,
 * not by a video element the browser would keep the display awake for. The browser drops the lock while the tab is
 * hidden, so it is asked for again when the tab shows. Browsers without the API, and pages served over plain HTTP,
 * where it is missing, keep their own idle rules.
 */
internal val screenWake = WakeHolds(acquire = ::lockScreen, release = ::unlockScreen)

private var wanted = false
private var lock: JsAny? = null
private val onVisibilityChange = { _: Event -> if (wanted && pageVisible()) requestLock() }

private fun lockScreen() {
    wanted = true
    document.addEventListener("visibilitychange", onVisibilityChange)
    requestLock()
}

private fun unlockScreen() {
    wanted = false
    document.removeEventListener("visibilitychange", onVisibilityChange)
    lock?.let(::releaseWakeLock)
    lock = null
}

/** A lock granted after the screen let go is returned at once; a newer one replaces the one the tab may still hold. */
private fun requestLock() {
    requestWakeLock { granted ->
        if (wanted) {
            lock?.let(::releaseWakeLock)
            lock = granted
        } else {
            releaseWakeLock(granted)
        }
    }
}

private fun requestWakeLock(onGranted: (JsAny) -> Unit) {
    js(
        "if ('wakeLock' in navigator) { navigator.wakeLock.request('screen').then(function (lock) { onGranted(lock); }, function () {}); }",
    )
}

private fun releaseWakeLock(lock: JsAny) {
    js("lock.release().catch(function () {});")
}

private fun pageVisible(): Boolean = js("document.visibilityState === 'visible'")
