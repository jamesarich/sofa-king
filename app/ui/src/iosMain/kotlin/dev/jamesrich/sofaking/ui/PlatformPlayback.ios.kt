package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.MediaSessionHandle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/** The app's page in the Settings app, where iOS keeps its caption style under Accessibility. */
@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? = remember {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
    if (url == null) {
        null
    } else {
        { UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null) }
    }
}

internal actual val supportsPlaybackPreferences: Boolean = false

internal actual val supportsNightListening: Boolean = false

internal actual val supportsCaptionSync: Boolean = false

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) = Unit

@Composable
internal actual fun ReleaseOnPageExit(sessions: PlaybackSessions) = Unit

@Composable
internal actual fun KeepPlayingInBackground(session: MediaSessionHandle?) = Unit

@Composable
internal actual fun rememberSurroundTestPlayer(): SurroundTestPlayer? = null
