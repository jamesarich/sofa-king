package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable

/** AVPlayerLayer draws the stream's captions in the viewer's iOS caption style; the app renders no cues here. */
@Composable
internal actual fun rememberSystemCaptionAppearance(): CaptionAppearance? = null
