package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MusicQueue
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.KeepPlayingInBackground
import dev.jamesrich.sofaking.ui.player.MediaSessionHandle
import dev.jamesrich.sofaking.ui.player.MusicPlayback
import dev.jamesrich.sofaking.ui.player.nowPlaying
import dev.jamesrich.sofaking.ui.player.songs

/**
 * The app's music on the shared player: it follows the player and the sessions for as long as the app is composed,
 * and while a song is loaded it holds the system media session, whose next and previous step through the queue, in
 * front of the app or behind it ([KeepPlayingInBackground]), and keeps the player at the app's volume (the guide's
 * preview mutes it).
 */
@Composable
internal fun rememberMusicPlayback(playback: AppPlayback, sessions: PlaybackSessions) = run {
    val player = playback.player
    val scope = rememberCoroutineScope()
    val music = remember(player, sessions) { MusicPlayback(player, sessions.songs(), scope) }
    LaunchedEffect(music) { music.run() }
    val song = music.song
    if (song != null) {
        var session by remember { mutableStateOf<MediaSessionHandle?>(null) }
        DisposableEffect(music) {
            val opened = player.openMediaSession { step -> if (step > 0) music.next() else music.previous() }
            session = opened
            onDispose {
                opened.close()
                session = null
            }
        }
        LaunchedEffect(song, session) { session?.publish(song.nowPlaying()) }
        KeepPlayingInBackground(session)
        LaunchedEffect(player, playback.volume) {
            snapshotFlow { playback.volume.gain }.collect { player.volume = it }
        }
    }
    music
}

/**
 * Plays [item] where it plays: a song as music, on through [album] (its album's songs, as its page lists them)
 * shuffled or not, with Now Playing opened over the page (Back from it focuses [item]); anything else full screen.
 */
internal fun AppShellState.playItem(
    music: MusicPlayback,
    item: MediaItem,
    fromStart: Boolean = false,
    album: List<MediaItem> = listOf(item),
) {
    if (item.kind != ItemKind.TRACK) {
        push(Route.Vod(item.id, fromStart))
        return
    }
    MusicQueue.of(album, start = item.id)?.let(music::play)
    openNowPlaying(returnTo = item.id)
}

/** Plays [songs] shuffled, from a random one, with Now Playing opened over the page. */
internal fun AppShellState.shuffle(music: MusicPlayback, songs: List<MediaItem>) {
    val queue = MusicQueue.of(songs, shuffled = true) ?: return
    music.play(queue)
    openNowPlaying()
}

/** Opens Now playing; Back focuses [returnTo] on the page below, or, [fromHeader], the header's song button. */
internal fun AppShellState.openNowPlaying(returnTo: String? = null, fromHeader: Boolean = false) {
    nowPlayingReturn = NowPlayingReturn(returnTo, fromHeader)
    push(Route.NowPlaying)
}
