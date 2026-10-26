package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.MediaSessionHandle
import dev.jamesrich.sofaking.ui.player.VlcSurroundTest

@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? = null

internal actual val supportsPlaybackPreferences: Boolean = false

internal actual val supportsNightListening: Boolean = true

internal actual val supportsCaptionSync: Boolean = false

@Composable
internal actual fun rememberSurroundTestPlayer(): SurroundTestPlayer? {
    val player = remember { VlcSurroundTest() }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) = Unit

@Composable
internal actual fun ReleaseOnPageExit(sessions: PlaybackSessions) = Unit

@Composable
internal actual fun KeepPlayingInBackground(session: MediaSessionHandle?) = Unit
