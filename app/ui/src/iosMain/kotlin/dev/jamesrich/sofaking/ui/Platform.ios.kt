package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification
import platform.UIKit.UIApplication

/** iPhone and iPad; Apple TV is not a target. */
@Composable
internal actual fun rememberIsTelevision(): Boolean = false

/**
 * VoiceOver reads the grid as focus moves; a channel opening full screen would cut it off, as TalkBack's would.
 * Followed while the screen is up, since VoiceOver can be switched on over an open guide.
 */
@Composable
internal actual fun rememberScreenReaderActive(): Boolean {
    var active by remember { mutableStateOf(UIAccessibilityIsVoiceOverRunning()) }
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIAccessibilityVoiceOverStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { active = UIAccessibilityIsVoiceOverRunning() }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    return active
}

@Composable
internal actual fun KeepScreenOn(on: Boolean) {
    DisposableEffect(on) {
        UIApplication.sharedApplication.idleTimerDisabled = on
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

/** The guide opens its text field; the system keyboard's own dictation key takes speech. */
@Composable
internal actual fun rememberVoiceSearch(prompt: String, onResult: (String?) -> Unit): () -> Boolean = { false }

internal actual val KeyEvent.repeatCountOrNull: Int? get() = null

internal actual val KeyEvent.isLongPress: Boolean get() = false
