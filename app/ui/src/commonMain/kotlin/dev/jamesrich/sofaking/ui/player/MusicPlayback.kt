package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MusicQueue
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val log = Logger.withTag("SofaKing")

/** The server's side of the songs: one session per song, as the library player opens an item's. */
internal interface SongSessions {
    /** The stream the app holds now, a song's or anything else's. */
    val current: StateFlow<PlaybackStream?>

    /** A stream the server replaced under the held session. */
    val replaced: Flow<PlaybackStream>

    /** Opens [itemId] from [startMs], releasing whatever was held. */
    suspend fun play(itemId: String, profile: PlaybackProfile, startMs: Long): PlaybackStream

    /** Lets [itemId]'s session go, and forgets it. */
    fun release(itemId: String)
}

/** The library sessions, as the songs use them. */
internal fun PlaybackSessions.songs(): SongSessions = object : SongSessions {
    override val current = this@songs.current
    override val replaced = this@songs.replaced

    override suspend fun play(itemId: String, profile: PlaybackProfile, startMs: Long) =
        this@songs.play(itemId, profile, startMs)

    override fun release(itemId: String) = releaseAsync(forget = true, itemId = itemId)
}

/**
 * The music the app plays on the shared [player], apart from any page: the [queue], and each song's session, so a
 * song plays on while the viewer browses and the next starts when it ends. Anything else that takes the player (a
 * channel, a movie) ends the music; Settings, or the app leaving the foreground while the song is paused, only
 * [hold]s it, and Play opens the song again where it was.
 */
@Stable
internal class MusicPlayback(
    private val player: LivePlayer,
    private val sessions: SongSessions,
    private val scope: CoroutineScope,
) {
    var queue by mutableStateOf<MusicQueue?>(null)
        private set

    /** The failure the song showed, as the failure card names it; null while it plays. */
    var failure by mutableStateOf<String?>(null)
        private set

    /** True while a song's session is being opened. */
    var opening by mutableStateOf(false)
        private set

    /** The stream of the song the player holds; null before the first, and while the song is [hold]. */
    private var stream by mutableStateOf<PlaybackStream?>(null)

    private var pendingSeek: Long? = null
    private var heldAtMs = 0L
    private var open: Job? = null

    val song: MediaItem? get() = queue?.current

    /** The shared player's state, for the play and pause button. */
    val status: StateFlow<PlayerStatus> get() = player.status

    /** True while the player plays the song, or is about to: it plays on behind the app. */
    val playingOn: Boolean get() = loaded && player.playWhenReady && player.status.value.state != PlaybackState.IDLE

    /** True while a song is stopped for now ([hold]), or not yet open: Play opens it. */
    val held: Boolean get() = song != null && !loaded

    /** True while the player holds the song's stream, so its play, pause and seek act on the song. */
    private val loaded: Boolean get() = stream?.let { it.url == player.currentStreamUrl } == true

    /** Where the song is, in the song. */
    val positionMs: Long get() = stream?.takeIf { loaded }?.let { player.positionMs + it.offsetMs } ?: heldAtMs

    /** The song's length, once known. */
    val durationMs: Long? get() = stream?.itemDurationMs ?: song?.durationMs

    /** Plays [queue] from its song. */
    fun play(queue: MusicQueue) {
        this.queue = queue
        openSong(0)
    }

    /** The next song; nothing after the last unless the queue repeats. */
    fun next() {
        val next = queue?.next() ?: return
        queue = next
        openSong(0)
    }

    /** The song from its start when it is past [RESTART_MS] or the first, else the one before it. */
    fun previous() {
        val current = queue ?: return
        val previous = current.previous()
        if (positionMs > RESTART_MS || previous == current) {
            seek(0)
        } else {
            queue = previous
            openSong(0)
        }
    }

    /** Pauses or plays; a song [hold] opens again where it was. */
    fun togglePause() {
        when {
            song == null -> Unit
            !loaded || player.status.value.state == PlaybackState.IDLE -> openSong(heldAtMs)
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    /** Moves to [targetMs] in the song: in the file the player holds, or a conversion opened again there. */
    fun seek(targetMs: Long) {
        val held = stream
        when {
            held == null || !loaded -> heldAtMs = targetMs
            held.mode == Wire.VodMode.DIRECT -> player.seekTo(targetMs)
            else -> openSong(targetMs)
        }
    }

    /** The same songs in another order or repeat, as Shuffle and Repeat [change] them; the song plays on. */
    fun reorder(change: (MusicQueue) -> MusicQueue) {
        queue = queue?.let(change)
    }

    /** Stops the music for now: the player stops and the session goes free, and Play picks the song up again. */
    fun hold() {
        if (!loaded) return
        heldAtMs = positionMs
        open?.cancel()
        player.stop()
        song?.let { sessions.release(it.id) }
        stream = null
    }

    /**
     * Ends the music: the queue empties, and the player stops on the song and its session goes free unless another
     * already took them ([release] false).
     */
    fun stop(release: Boolean = true) {
        open?.cancel()
        if (release && loaded) player.stop()
        if (release) song?.let { sessions.release(it.id) }
        stream = null
        queue = null
        failure = null
        heldAtMs = 0
    }

    /** Follows the player and the sessions for as long as the app runs. */
    suspend fun run(): Unit = coroutineScope {
        launch { player.status.collect(::onStatus) }
        launch { player.errors.collect { if (loaded) failure = it.code } }
        launch {
            // A channel or a movie took the player: the music is over.
            sessions.current.collect { held ->
                if (held == null || opening) return@collect
                if (song != null && held.itemId != song?.id) stop(release = false)
            }
        }
        launch {
            sessions.replaced.collect { replaced ->
                if (replaced.itemId == song?.id && loaded) {
                    player.replaceStream(replaced)
                    stream = replaced
                    player.prepare()
                }
            }
        }
    }

    private fun onStatus(status: PlayerStatus) {
        if (!loaded) return
        when (status.state) {
            PlaybackState.READY -> {
                failure = null
                pendingSeek?.let { player.seekTo(it) }
                pendingSeek = null
            }

            PlaybackState.ENDED -> {
                val next = queue?.next(ended = true)
                if (next == null) {
                    stop()
                } else {
                    queue = next
                    openSong(0)
                }
            }

            else -> Unit
        }
    }

    /** Opens the song's session from [startMs] and plays it. */
    private fun openSong(startMs: Long) {
        val song = song ?: return
        open?.cancel()
        failure = null
        heldAtMs = startMs
        // Set before the launch, so the session this open releases does not read as another taking the player.
        opening = true
        open = scope.launch {
            try {
                runCatchingCancellable { sessions.play(song.id, player.playbackProfile, startMs) }
                    .onSuccess { s ->
                        player.stop()
                        player.setStream(s, song.nowPlaying())
                        stream = s
                        pendingSeek = (s.startAtMs - s.offsetMs).takeIf { it > 0 }
                        player.playWhenReady = true
                        player.prepare()
                    }
                    .onFailure { e ->
                        log.w { "music: ${song.id} failed: ${e.message}" }
                        failure = failureCode(e)
                    }
            } finally {
                if (open === coroutineContext[Job]) opening = false
            }
        }
    }

    companion object {
        /** Past this far into a song, Previous starts it again rather than going back a song. */
        const val RESTART_MS = 3_000L
    }
}

/** What the system's media surfaces show for a song: its title, its artist and its album's cover. */
internal fun MediaItem.nowPlaying(): NowPlaying = NowPlaying(title, songLine(this).orEmpty(), poster)
