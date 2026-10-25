package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.withStateAtLeast
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.MatchDisplayFrameRate
import dev.jamesrich.sofaking.ui.ReleaseOnPageExit
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.PlaybackState
import dev.jamesrich.sofaking.ui.player.PlayerVolume
import dev.jamesrich.sofaking.ui.player.rememberLivePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * One player for the guide preview and the full screen, so OK on the previewed channel continues its stream, and one
 * server session behind it, promoted from preview to watch in place; its caption timing; and the players' own volume
 * where the app draws it.
 */
@Stable
internal class AppPlayback(val captionSync: CaptionSync, val player: LivePlayer, val volume: PlayerVolume)

@Composable
internal fun rememberAppPlayback(): AppPlayback {
    val captionSync = remember { CaptionSync() }
    val player = rememberLivePlayer(captionSync)
    val volume = remember(player) { PlayerVolume(adjustable = player.drawsVolumeControl) }
    return remember(captionSync, player, volume) { AppPlayback(captionSync, player, volume) }
}

/**
 * The players' own volume where the app draws it: restored once the settings are read, and kept a moment after it
 * settles through [onSave], so a slider drag is one write. [settings] reads the settings as they are now.
 */
@Composable
internal fun VolumeSaveEffect(
    volume: PlayerVolume,
    settingsRead: Boolean,
    settings: () -> AppSettings?,
    onSave: (level: Int, muted: Boolean) -> Unit,
) {
    val currentSettings by rememberUpdatedState(settings)
    val currentOnSave by rememberUpdatedState(onSave)
    LaunchedEffect(volume, settingsRead) {
        val stored = currentSettings() ?: return@LaunchedEffect
        if (!volume.adjustable) return@LaunchedEffect
        volume.restore(stored.volume, stored.volumeMuted)
        snapshotFlow { volume.level to volume.muted }.collectLatest { (level, muted) ->
            delay(VOLUME_SAVE_MS)
            val saved = currentSettings() ?: return@collectLatest
            if (saved.volume != level || saved.volumeMuted != muted) currentOnSave(level, muted)
        }
    }
}

/**
 * The server replaced the stream under the held session (new epoch, or a session it dropped and the app reopened):
 * the player reopens the item it holds for that channel at the new URL, metadata intact. A library item's new stream
 * may start elsewhere in it, so its own screen loads it and seeks.
 */
@Composable
internal fun StreamReplacedEffect(playback: AppPlayback, sessions: PlaybackSessions, lifecycle: Lifecycle) {
    val player = playback.player
    val captionSync = playback.captionSync
    LaunchedEffect(sessions, player) {
        sessions.replaced.collect { stream ->
            if (stream.itemId != null) return@collect
            lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                if (player.currentChannelId != stream.channelId || player.currentStreamUrl == stream.url) {
                    return@withStateAtLeast
                }
                captionSync.effectiveUs = captionSync.advanceUs
                player.replaceStream(stream)
                player.prepare()
            }
        }
    }
}

/** The player follows the settings: captions, playback preferences, and the display mode on [top]. */
@Composable
internal fun PlayerPreferenceEffects(player: LivePlayer, settings: AppSettings?, top: Route) {
    // Before the first prepare: switching text tracks on a playing live stream restarts it. A library item's
    // subtitles are the app's own, drawn over the picture, so the player's captions stay off under them; libVLC and
    // AVPlayer would otherwise draw the file's own text track as well.
    val captionsEnabled = settings?.captionsEnabled?.let { it && top !is Route.Vod }
    LaunchedEffect(captionsEnabled) {
        if (captionsEnabled != null) player.setCaptionsEnabled(captionsEnabled)
    }
    val playback = settings?.playback
    LaunchedEffect(playback) {
        if (playback != null) player.setPlaybackPreferences(playback)
    }
    // Only the full screen asks for a display mode: the guide's preview zaps too often for a
    // set that blanks on each switch.
    MatchDisplayFrameRate(player, enabled = playback?.matchFrameRate == true && top.playsFullScreen)
}

/**
 * What [top] plays: the guide only where it [guidePlays], the library and Settings nothing. Without the preview band
 * (a phone, a short window) the guide plays nothing: the stream the full screen leaves behind stops and its session
 * goes free, rather than playing on unseen and holding a tuner.
 */
@Composable
internal fun RouteStreamEffects(
    shell: AppShellState,
    top: Route,
    playback: AppPlayback,
    sessions: PlaybackSessions,
    guidePlays: Boolean,
) {
    val player = playback.player
    LaunchedEffect(top is Route.Guide, guidePlays) {
        if (top is Route.Guide && !guidePlays) {
            player.stop()
            sessions.release(forget = true)
        }
    }
    // The preview starts without a caption lead; the full screen sets each channel's own. Settings may change
    // the server, so it releases the session outright rather than keeping it to reopen.
    LaunchedEffect(top) {
        shell.relockUnlessInSettings()
        when (top) {
            Route.Guide -> playback.captionSync.advanceUs = 0L

            Route.Settings -> {
                player.stop()
                sessions.release(forget = true)
            }

            is Route.Player, is Route.Multiview, is Route.Vod, is Route.Recording -> Unit

            // The library, the recordings and On Later play nothing until one is picked, so the
            // guide's preview stops on the way in.
            Route.OnLater, Route.Library, is Route.LibraryGrid, is Route.LibrarySearch, is Route.LibraryItem,
            Route.Recordings,
            -> {
                player.stop()
                sessions.release(forget = true)
            }

            // Reached from Settings, which already stopped the stream.
            Route.Channels, Route.StartupChannel -> Unit
        }
    }
}

/**
 * Stopped, not paused: the TV's one hardware decoder and the server's session go free while another app is in front.
 * The item and playWhenReady survive; on return, while the page [plays], a fresh session is opened for the same
 * channel and intent and the item is prepared at its URL, at the live edge or still paused. A library item's screen
 * reopens it itself, where it was, and the library's other pages hold no stream.
 */
@Composable
internal fun ForegroundEffects(
    playback: AppPlayback,
    sessions: PlaybackSessions,
    lifecycleOwner: LifecycleOwner,
    plays: Boolean,
) {
    val player = playback.player
    val captionSync = playback.captionSync
    val scope = rememberCoroutineScope()
    val currentPlays by rememberUpdatedState(plays)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, lifecycleOwner) {
        player.stop()
        sessions.releaseAsync()
    }
    ReleaseOnPageExit(sessions)
    LifecycleEventEffect(Lifecycle.Event.ON_START, lifecycleOwner) {
        if (currentPlays && player.hasItem && player.status.value.state == PlaybackState.IDLE) {
            captionSync.effectiveUs = captionSync.advanceUs
            scope.launch { resume(player, sessions) }
        }
    }
}

/**
 * Back in front: a fresh session for the channel and intent released on stop, played at its new URL. The player can
 * still hold an older channel's stream, one whose session was released before the stop; that URL is gone, so the
 * reopened channel replaces it rather than the player loading a released session's playlist.
 */
private suspend fun resume(player: LivePlayer, sessions: PlaybackSessions) {
    val stream = try {
        sessions.reopen()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Preparing the stale item fails on the player, where the screen's own reconnect takes over.
        null
    }
    if (!player.hasItem) return
    when {
        stream == null || stream.itemId != null -> Unit

        stream.channelId == player.currentChannelId -> player.replaceStream(stream)

        else -> {
            player.stop()
            player.setStream(stream)
        }
    }
    if (player.status.value.state == PlaybackState.IDLE) player.prepare()
}

private const val VOLUME_SAVE_MS = 500L
