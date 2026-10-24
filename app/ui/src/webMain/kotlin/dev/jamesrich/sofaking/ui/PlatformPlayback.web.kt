package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.ui.player.LivePlayer

@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? = null

internal actual fun captionEngineName(): String = "the browser's video element"

internal actual val supportsPlaybackPreferences: Boolean = false

internal actual val supportsCaptionSync: Boolean = false

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) = Unit
