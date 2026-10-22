package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller

/**
 * Back on every platform: the remote's or system Back on Android, the swipe on iOS, Escape on desktops and the
 * web. While [enabled], [onBack] takes it; otherwise it goes on to the host (on Android, finishing the activity).
 */
@Composable
internal fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}

/** True on a TV (Android TV, Google TV), which keeps the sofa layout and the remote's overscan; false elsewhere. */
@Composable
internal expect fun rememberIsTelevision(): Boolean

/** True when the platform's own clock preference is 24-hour (Android's system setting, else the locale's pattern). */
@Composable
internal expect fun rememberSystem24HourClock(): Boolean

/**
 * Opens the platform's own caption settings (Android's captioning preferences, the app's page in iOS Settings),
 * or null where there is no such page, so Settings leaves the row out.
 */
@Composable
internal expect fun rememberSystemCaptionSettings(): (() -> Unit)?

/** The engine that draws captions on this platform, named for the Settings help text. */
internal expect fun captionEngineName(): String

/** True where the engine honors [dev.jamesrich.sofaking.data.PlaybackPreferences] (Android's Media3); Settings hides the Playback section elsewhere. */
internal expect val supportsPlaybackPreferences: Boolean

/** True where captions are drawn through `CaptionSync` (Android's Media3), so smoothing and timing apply; Settings hides those rows elsewhere. */
internal expect val supportsCaptionSync: Boolean

/** True where the live player reads the server's ad marks (Android's Media3), so the break screen can cover ads; Settings hides the row elsewhere. */
internal expect val supportsLiveBreaks: Boolean

/**
 * What Get the update opens for a published [latest] build: the Android package on Android, the release page on a
 * desktop or in a browser, or null where no package is published (iOS), so Settings says so instead.
 */
internal expect fun updateDownloadUrl(latest: PublishedBuild): String?

/**
 * Installs a published build from inside the app: on Android, unless an app store installed it; null elsewhere,
 * where Get the update opens [updateDownloadUrl] instead.
 */
@Composable
internal expect fun rememberUpdateInstaller(): UpdateInstaller?

/**
 * While [enabled], switches the display to a mode that matches the frame rate of the video [player] plays, and
 * restores the default when it leaves composition or is disabled; nothing happens on platforms without display modes.
 */
@Composable
internal expect fun MatchDisplayFrameRate(player: dev.jamesrich.sofaking.ui.player.LivePlayer, enabled: Boolean)

/** True while a screen reader explores by touch (TalkBack), which a channel opening full screen would cut off. */
@Composable
internal expect fun rememberScreenReaderActive(): Boolean

/** Keeps the display awake while [on]; released when this leaves composition. */
@Composable
internal expect fun KeepScreenOn(on: Boolean)

/**
 * Speech input for the guide search. The returned function starts it and is false when the platform has no
 * recognizer, so the caller opens the text field instead; [onResult] gets the spoken query, or null when dismissed.
 */
@Composable
internal expect fun rememberVoiceSearch(prompt: String, onResult: (String?) -> Unit): () -> Boolean

/**
 * How often the platform has repeated this key-down while it is held, or null where key events do not say
 * (the caller counts repeats itself); Android TV reports it and a long-press flag on the native event.
 */
internal expect val KeyEvent.repeatCountOrNull: Int?

/** True when the platform marked this key-down as a long press; false where it never does. */
internal expect val KeyEvent.isLongPress: Boolean
