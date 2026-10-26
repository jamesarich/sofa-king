package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.HLS_MIME_TYPE
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MusicQueue
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlaybackTest {
    private val player = MusicTestPlayer()
    private val sessions = TestSongSessions()

    private fun song(id: String) = MediaItem(id, "lib", ItemKind.TRACK, "Song $id", parentId = "a1")

    private val album = MusicQueue.of(listOf(song("1"), song("2"), song("3")))!!

    private fun TestScope.music(): MusicPlayback {
        val music = MusicPlayback(player, sessions, this)
        backgroundScope.launch { music.run() }
        runCurrent()
        return music
    }

    private fun TestScope.end() {
        player.status(PlaybackState.ENDED)
        runCurrent()
    }

    @Test
    fun `songs play on through the queue and the music ends after the last`() = runTest {
        val music = music()
        music.play(album)
        runCurrent()
        assertEquals("u1@0", player.currentStreamUrl)
        end()
        assertEquals("2", music.song?.id)
        assertEquals("u2@0", player.currentStreamUrl)
        end()
        end()
        assertNull(music.queue, "nothing after the last song")
        assertEquals(listOf("1", "2", "3"), sessions.opened.map { it.first })
        assertEquals(listOf("3"), sessions.released)
    }

    @Test
    fun `a song that repeats plays again and a skip moves on`() = runTest {
        val music = music()
        music.play(album)
        runCurrent()
        music.reorder(MusicQueue::nextRepeat)
        music.reorder(MusicQueue::nextRepeat)
        end()
        assertEquals("1", music.song?.id)
        music.next()
        runCurrent()
        assertEquals("2", music.song?.id)
        assertEquals(listOf("1", "1", "2"), sessions.opened.map { it.first })
    }

    @Test
    fun `a channel or a movie taking the player ends the music`() = runTest {
        val music = music()
        music.play(album)
        runCurrent()
        sessions.current.value = sessions.stream("movie", 0)
        runCurrent()
        assertNull(music.queue)
    }

    @Test
    fun `a song held for now opens again where it was`() = runTest {
        val music = music()
        music.play(album)
        runCurrent()
        player.positionMs = 42_000
        music.hold()
        assertEquals(listOf("1"), sessions.released)
        assertEquals(42_000, music.positionMs)
        assertEquals("1", music.song?.id, "the queue waits")
        music.togglePause()
        runCurrent()
        assertEquals("1" to 42_000L, sessions.opened.last())
    }

    @Test
    fun `previous starts a song again once it is a few seconds in and otherwise goes back a song`() = runTest {
        val music = music()
        music.play(album.next()!!)
        runCurrent()
        player.positionMs = 5_000
        music.previous()
        assertEquals(listOf(0L), player.seeks)
        assertEquals("2", music.song?.id)
        player.positionMs = 1_000
        music.previous()
        runCurrent()
        assertEquals("1", music.song?.id)
    }
}

/** The songs' sessions: each open is a file played as it is, named for the song and where it starts. */
private class TestSongSessions : SongSessions {
    override val current = MutableStateFlow<PlaybackStream?>(null)
    override val replaced = MutableSharedFlow<PlaybackStream>()
    val opened = mutableListOf<Pair<String, Long>>()
    val released = mutableListOf<String>()

    fun stream(itemId: String, startMs: Long) = PlaybackStream(
        "",
        "s$itemId",
        "u$itemId@$startMs",
        HLS_MIME_TYPE,
        epoch = 1,
        PlaybackIntent.WATCH,
        seekable = true,
        itemId = itemId,
        mode = Wire.VodMode.DIRECT,
        startAtMs = startMs,
        itemDurationMs = 180_000,
    )

    override suspend fun play(itemId: String, profile: PlaybackProfile, startMs: Long): PlaybackStream {
        opened += itemId to startMs
        return stream(itemId, startMs).also { current.value = it }
    }

    override fun release(itemId: String) {
        released += itemId
        if (current.value?.itemId == itemId) current.value = null
    }
}

/** A player whose status the test moves, and which reports where it is told it is. */
private class MusicTestPlayer : StreamLivePlayer({ 0L }) {
    override var playWhenReady = true
    override var positionMs = 0L
    val seeks = mutableListOf<Long>()

    fun status(state: PlaybackState) {
        mutableStatus.value = PlayerStatus(state, isPlaying = state == PlaybackState.READY, playWhenReady)
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        this.positionMs = positionMs
    }

    override fun prepare() = status(PlaybackState.BUFFERING)

    override fun stop() = status(PlaybackState.IDLE)

    override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList())
    override val captionSync = CaptionSync()
    override val bufferedMs = 0L
    override val supportsCaptionLead = false
    override val hasTextTrack = false
    override val audioTracks: StateFlow<List<AudioTrack>> = MutableStateFlow(emptyList())
    override var volume = 1f

    override fun selectAudioTrack(id: String) = Unit

    override fun streamInfo() = StreamInfo()

    override fun setCaptionsEnabled(enabled: Boolean) = Unit

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = error("not used")

    @Composable
    override fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit) = Unit

    override fun release() = Unit
}
