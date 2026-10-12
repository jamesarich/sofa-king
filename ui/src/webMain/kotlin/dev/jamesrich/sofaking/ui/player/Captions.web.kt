package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable

/** The browser draws the stream's in-band captions in the video element; the app renders no cues here. */
@Composable
internal actual fun rememberSystemCaptionAppearance(): CaptionAppearance? = null
