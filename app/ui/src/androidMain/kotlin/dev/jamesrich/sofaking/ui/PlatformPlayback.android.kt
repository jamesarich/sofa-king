package dev.jamesrich.sofaking.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.MatchExoDisplayFrameRate

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

internal actual fun captionEngineName(): String = "Media3"

internal actual val supportsPlaybackPreferences: Boolean = true

internal actual val supportsCaptionSync: Boolean = true

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) =
    MatchExoDisplayFrameRate(player, enabled)

@Composable
internal actual fun ReleaseOnPageExit(sessions: PlaybackSessions) = Unit
