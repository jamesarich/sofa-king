package dev.jamesrich.sofaking.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaSession
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.player.ExoSurroundTest
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.MatchExoDisplayFrameRate
import dev.jamesrich.sofaking.ui.player.MediaSessionHandle
import dev.jamesrich.sofaking.ui.player.MusicService

/** The system's captioning preferences; a build without that activity (some set-top boxes) gets no row. */
@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        val intent = Intent(Settings.ACTION_CAPTIONING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            null
        } else {
            {
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    }
}

internal actual val supportsPlaybackPreferences: Boolean = true

internal actual val supportsNightListening: Boolean = true

internal actual val playsHlsOnly: Boolean = false

internal actual val supportsCaptionSync: Boolean = true

internal actual val supportsCrashReports: Boolean = true

@Composable
internal actual fun rememberSurroundTestPlayer(): SurroundTestPlayer? {
    val context = LocalContext.current.applicationContext
    val player = remember(context) { ExoSurroundTest(context) }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) =
    MatchExoDisplayFrameRate(player, enabled)

@Composable
internal actual fun ReleaseOnPageExit(sessions: PlaybackSessions) = Unit

/** The music's media session goes to [MusicService] while a song is loaded, which keeps it playing behind the app. */
@Composable
internal actual fun KeepPlayingInBackground(session: MediaSessionHandle?) {
    val context = LocalContext.current.applicationContext
    val media = session?.platformSession as? MediaSession
    DisposableEffect(media) {
        media?.let { MusicService.start(context, it) }
        onDispose { if (media != null) MusicService.stop(context) }
    }
}
