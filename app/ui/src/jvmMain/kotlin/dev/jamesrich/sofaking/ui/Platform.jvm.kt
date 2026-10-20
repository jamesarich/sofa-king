package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.jamesrich.sofaking.ui.player.LivePlayer
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import dev.jamesrich.sofaking.data.PublishedBuild

/** A desktop window, sized and placed by its user. */
@Composable
internal actual fun rememberIsTelevision(): Boolean = false

/** The default locale's short time pattern: an hour-of-day field (H or k) means a 24-hour clock. */
@Composable
internal actual fun rememberSystem24HourClock(): Boolean = remember {
    val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    (format as? java.text.SimpleDateFormat)?.toPattern()?.let { pattern -> pattern.contains('H') || pattern.contains('k') } ?: false
}

/** Desktop screen readers do not explore by touch; the preview may open full screen. */
@Composable
internal actual fun rememberScreenReaderActive(): Boolean = false

/** Holds the display awake while [on] on Windows and macOS; see [displayWake]. */
@Composable
internal actual fun KeepScreenOn(on: Boolean) {
    DisposableEffect(on) {
        val hold = if (on) displayWake?.hold() else null
        onDispose { hold?.close() }
    }
}

/** No speech recognizer on the JVM; the guide opens its text field. */
@Composable
internal actual fun rememberVoiceSearch(prompt: String, onResult: (String?) -> Unit): () -> Boolean = { false }

internal actual val KeyEvent.repeatCountOrNull: Int? get() = null

internal actual val KeyEvent.isLongPress: Boolean get() = false

@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? = null

internal actual fun captionEngineName(): String = "libVLC"

internal actual val supportsPlaybackPreferences: Boolean = false

internal actual val supportsCaptionSync: Boolean = false

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) = Unit

internal actual fun updateDownloadUrl(latest: PublishedBuild): String? = latest.pageUrl
