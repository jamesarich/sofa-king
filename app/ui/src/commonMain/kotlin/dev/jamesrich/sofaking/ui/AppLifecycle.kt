package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The app's own lifecycle, which the app provides above Navigation 3. Each page's `LocalLifecycleOwner` is its
 * entry's, which also stops when the page is left; the players and the preview stop and reopen their stream
 * only when the app leaves and returns to the foreground, so they read this one.
 */
internal val LocalAppLifecycleOwner = staticCompositionLocalOf<LifecycleOwner?> { null }

/** The app's lifecycle owner, or the nearest one where the app has not provided it (previews, tests). */
internal val appLifecycleOwner: LifecycleOwner
    @Composable get() = LocalAppLifecycleOwner.current ?: LocalLifecycleOwner.current
