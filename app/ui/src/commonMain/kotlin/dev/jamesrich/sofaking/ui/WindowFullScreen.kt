package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Stable

/**
 * The host window's own full screen, where the viewer switches it from the app: a desktop window.
 * [isFullScreen] is snapshot state, so the players' button follows a change made outside the app (the window
 * manager).
 */
@Stable
interface WindowFullScreen {
    val isFullScreen: Boolean

    fun toggle()
}
