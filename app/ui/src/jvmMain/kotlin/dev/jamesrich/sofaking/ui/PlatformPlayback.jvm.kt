package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.MediaSessionHandle

@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? = null

internal actual val supportsPlaybackPreferences: Boolean = false

internal actual val supportsNightListening: Boolean = true

internal actual val supportsCaptionSync: Boolean = false

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) = Unit

@Composable
internal actual fun ReleaseOnPageExit(sessions: PlaybackSessions) = Unit

@Composable
internal actual fun KeepPlayingInBackground(session: MediaSessionHandle?) = Unit
