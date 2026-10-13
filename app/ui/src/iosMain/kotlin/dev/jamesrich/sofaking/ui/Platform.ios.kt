package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIApplication

/** VoiceOver reads the grid as focus moves; a channel opening full screen would cut it off, as TalkBack's would. */
@Composable
internal actual fun rememberScreenReaderActive(): Boolean = remember { UIAccessibilityIsVoiceOverRunning() }

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

/** The app runs in landscape, where iOS already hides the status bar. */
@Composable
internal actual fun HideSystemBars(on: Boolean) = Unit
