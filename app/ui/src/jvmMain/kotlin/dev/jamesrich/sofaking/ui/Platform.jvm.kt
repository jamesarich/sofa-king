package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.lifecycle.LifecycleOwner
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller
import dev.jamesrich.sofaking.ui.player.LivePlayer

/**
 * A desktop window, sized and placed by its user, unless started with `-Dsofaking.tv=true`, which lays the app out as
 * on a TV with the keyboard as the remote, for working on the TV layout and testing it without one.
 */
@Composable
internal actual fun rememberIsTelevision(): Boolean = remember { System.getProperty(TV_PROPERTY).toBoolean() }

internal const val TV_PROPERTY = "sofaking.tv"

/** The default locale's short time pattern: an hour-of-day field (H or k) means a 24-hour clock. */
@Composable
internal actual fun rememberSystem24HourClock(): Boolean = remember {
    val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    (format as? java.text.SimpleDateFormat)?.toPattern()?.let { pattern ->
        pattern.contains('H') || pattern.contains('k')
    }
        ?: false
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

internal actual val platformMovesFocusOnArrows: Boolean = false

internal actual val supportsCaptionSync: Boolean = false

internal actual val pageServerUrl: String? = null

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) = Unit

/** The window goes full screen through [WindowFullScreen], from the players; desktops have no picture-in-picture. */
@Composable
internal actual fun rememberPlayerWindow(player: LivePlayer, active: Boolean): Boolean = false

internal actual fun updateDownloadUrl(latest: PublishedBuild): String? = latest.pageUrl

@Composable
internal actual fun rememberUpdateInstaller(): UpdateInstaller? = null

@Composable
internal actual fun rememberAppLifecycleOwner(host: LifecycleOwner): LifecycleOwner = host

@Composable
internal actual fun ReleaseOnPageExit(sessions: PlaybackSessions) = Unit
