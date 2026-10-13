package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable

/** libVLC draws the stream's captions into the picture; the app's own style applies only where it renders cues. */
@Composable
internal actual fun rememberSystemCaptionAppearance(): CaptionAppearance? = null
