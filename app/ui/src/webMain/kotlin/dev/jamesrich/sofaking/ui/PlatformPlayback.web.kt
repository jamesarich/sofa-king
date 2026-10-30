package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.ui.player.LivePlayer

@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? = null

internal actual val supportsPlaybackPreferences: Boolean = false

internal actual val supportsNightListening: Boolean = false

internal actual val playsHlsOnly: Boolean = true

internal actual val supportsCaptionSync: Boolean = false

internal actual val supportsCrashReports: Boolean = false

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) = Unit

@Composable
internal actual fun rememberSurroundTestPlayer(): SurroundTestPlayer? = null
