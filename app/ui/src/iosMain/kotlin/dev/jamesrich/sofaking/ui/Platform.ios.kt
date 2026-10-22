package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSNotificationCenter
import platform.Foundation.currentLocale
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.Foundation.NSURL
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller

/** iPhone and iPad; Apple TV is not a target. */
@Composable
internal actual fun rememberIsTelevision(): Boolean = false

/** The current locale's hour template: a pattern without an AM/PM marker means a 24-hour clock, which iOS's own setting feeds. */
@Composable
internal actual fun rememberSystem24HourClock(): Boolean = remember {
    val pattern = NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale) ?: ""
    !pattern.contains('a')
}

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

/** The app's page in the Settings app, where iOS keeps its caption style under Accessibility. */
@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? = remember {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
    if (url == null) null else { { UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null) } }
}

internal actual fun captionEngineName(): String = "AVPlayer"

internal actual val supportsPlaybackPreferences: Boolean = false

internal actual val supportsCaptionSync: Boolean = false

internal actual val supportsLiveBreaks: Boolean = false

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) = Unit

/** The SwiftUI host owns the status bar, and the app offers no picture-in-picture on iOS. */
@Composable
internal actual fun rememberPlayerWindow(player: LivePlayer, active: Boolean): Boolean = false

internal actual fun updateDownloadUrl(latest: PublishedBuild): String? = null

@Composable
internal actual fun rememberUpdateInstaller(): UpdateInstaller? = null
