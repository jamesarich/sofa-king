package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import androidx.lifecycle.LifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

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
 * True where the platform moves focus on the arrow keys itself (Android maps the D-pad); Compose on the desktop, the
 * web and iOS moves it only on Tab, so the app moves it there ([arrowKeysMoveFocus]).
 */
internal expect val platformMovesFocusOnArrows: Boolean

/** The Kotlin targets this platform builds, by the names the open-source licenses list tags each library with. */
internal expect val licenseTargets: Set<String>

/** True while a screen reader explores by touch (TalkBack), which a channel opening full screen would cut off. */
@Composable
internal expect fun rememberScreenReaderActive(): Boolean

/** Keeps the display awake while [on]; released when this leaves composition. */
@Composable
internal expect fun KeepScreenOn(on: Boolean)

/**
 * The lifecycle the app plays by, from [host], the window's. In a browser a hidden tab is stopped whatever focus says,
 * and a tab counts as back only once it has stayed visible for a moment: a page shown for an instant (a window
 * flicked past, a host that redraws a covered page) would otherwise open a session only to drop it. [host] itself
 * elsewhere.
 */
@Composable
internal expect fun rememberAppLifecycleOwner(host: LifecycleOwner): LifecycleOwner

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
