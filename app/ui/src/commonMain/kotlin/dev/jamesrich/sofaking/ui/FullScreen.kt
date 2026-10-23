package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.ui.player.LivePlayer

/**
 * The host window's own full screen, where the viewer switches it from the app: a desktop window or a browser page.
 * [isFullScreen] is snapshot state, so the players' button follows a change made outside the app (the browser's
 * Escape, the window manager).
 */
@Stable
interface WindowFullScreen {
    val isFullScreen: Boolean

    fun toggle()
}

/** The window's full screen, or null where the app cannot switch it (Android and iOS, whose players fill the screen). */
val LocalWindowFullScreen = staticCompositionLocalOf<WindowFullScreen?> { null }

/** True while the app shows in the system's picture-in-picture window, where the players draw only the picture. */
internal val LocalInPictureInPicture = compositionLocalOf { false }

/** F in a player, or F11 on any page, switches the window's full screen; F elsewhere may be typed into a search. */
internal fun Key.togglesFullScreen(inPlayer: Boolean): Boolean = this == Key.F11 || (inPlayer && this == Key.F)

/**
 * The window around the players while [active], a full screen player on top: on Android the system bars hide (a
 * swipe from the edge shows them for a moment), and a phone or tablet shrinks the player into picture-in-picture
 * when the viewer leaves the app, so it keeps playing in a corner. Returns true while the app is in
 * picture-in-picture. Other platforms leave the window to [WindowFullScreen] and always return false.
 */
@Composable
internal expect fun rememberPlayerWindow(player: LivePlayer, active: Boolean): Boolean
