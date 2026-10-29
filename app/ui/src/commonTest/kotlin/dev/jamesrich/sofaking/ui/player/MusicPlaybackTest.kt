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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

    private val taste = TestTaste()

    private fun TestScope.music(): MusicPlayback {
        val music = MusicPlayback(player, sessions, this, taste)
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
    fun `a song held while it opens keeps its own start and not the last song's place`() = runTest {
        val music = music()
        music.play(album)
        runCurrent()
        player.positionMs = 42_000
        sessions.openMs = 5_000
        music.next()
        runCurrent()
        assertEquals(0, music.positionMs, "the next song is at its start while it opens")
        music.hold()
        advanceTimeBy(10_000)
        sessions.openMs = 0
        music.togglePause()
        runCurrent()
        assertEquals("2" to 0L, sessions.opened.last())
    }

    @Test
    fun `a song counts as played once it has played for half a minute and again each time it starts`() = runTest {
        val music = music()
        music.play(album)
        runCurrent()
        player.status(PlaybackState.READY)
        player.positionMs = 20_000
        advanceTimeBy(MusicPlayback.PLAY_CHECK_MS + 1)
        assertEquals(emptyList(), taste.plays)
        player.positionMs = 31_000
        advanceTimeBy(MusicPlayback.PLAY_CHECK_MS)
        advanceTimeBy(MusicPlayback.PLAY_CHECK_MS)
        assertEquals(listOf("1"), taste.plays, "once, however long it plays on")
        music.reorder(MusicQueue::nextRepeat)
        music.reorder(MusicQueue::nextRepeat)
        end()
        player.status(PlaybackState.READY)
        player.positionMs = 31_000
        advanceTimeBy(MusicPlayback.PLAY_CHECK_MS + 1)
        assertEquals(listOf("1", "1"), taste.plays, "a repeat is another play")
    }

    @Test
    fun `a paused song is not counted`() = runTest {
        val music = music()
        music.play(album)
        runCurrent()
        player.positionMs = 60_000
        advanceTimeBy(MusicPlayback.PLAY_CHECK_MS * 2)
        assertEquals(emptyList(), taste.plays, "buffering is not playing")
    }

    @Test
    fun `Favorite likes the song for the household and again unlikes it`() = runTest {
        val music = music()
        music.play(album)
        runCurrent()
        val first = music.song!!
        assertEquals(false, music.likes.isFavorite(first))
        music.likes.toggle(music.song!!)
        assertEquals(true, music.likes.isFavorite(first))
        music.likes.toggle(music.song!!)
        assertEquals(false, music.likes.isFavorite(first))
        runCurrent()
        assertEquals(listOf("1" to true, "1" to false), taste.likes)
        assertEquals(true, music.likes.isFavorite(song("9").copy(favorite = true)), "a song listed as liked is liked")
    }

    @Test
    fun `a like the server refuses goes back to what the song was`() = runTest {
        val music = music()
        music.play(album)
        runCurrent()
        val first = music.song!!
        taste.accepts = false
        music.likes.toggle(first)
        assertEquals(true, music.likes.isFavorite(first), "the like shows at once")
        runCurrent()
        assertEquals(false, music.likes.isFavorite(first))
    }

    @Test
    fun `a refused unlike leaves the song liked`() = runTest {
        val music = music()
        val liked = song("9").copy(favorite = true)
        taste.accepts = false
        music.likes.toggle(liked)
        assertEquals(false, music.likes.isFavorite(liked))
        runCurrent()
        assertEquals(true, music.likes.isFavorite(liked))
    }

    @Test
    fun `quick taps on one song reach the server one at a time in order`() = runTest {
        val music = music()
        val first = song("1")
        taste.hold = true
        music.likes.toggle(first)
        music.likes.toggle(first)
        music.likes.toggle(first)
        runCurrent()
        assertEquals(listOf("1" to true), taste.likes, "the next call waits for the one in flight")
        taste.release()
        runCurrent()
        assertEquals(listOf("1" to true, "1" to false, "1" to true), taste.likes)
        assertEquals(1, taste.mostAtOnce)
        assertEquals(true, music.likes.isFavorite(first))
    }

    @Test
    fun `a refused tap after an accepted one goes back to the accepted like`() = runTest {
        val music = music()
        val first = song("1")
        music.likes.toggle(first)
        runCurrent()
        taste.accepts = false
        music.likes.toggle(first)
        assertEquals(false, music.likes.isFavorite(first))
        runCurrent()
        assertEquals(true, music.likes.isFavorite(first))
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

/** What the server was told. */
private class TestTaste : SongTaste {
    val plays = mutableListOf<String>()
    val likes = mutableListOf<Pair<String, Boolean>>()

    /** Whether the server takes a like. */
    var accepts = true

    /** While set, a like waits in flight until [release]. */
    var hold = false
    var mostAtOnce = 0
    private var inFlight = 0
    private val gate = MutableStateFlow(0)

    fun release() {
        hold = false
        gate.value++
    }

    override fun played(songId: String) {
        plays += songId
    }

    override suspend fun favorite(songId: String, favorite: Boolean): Boolean {
        likes += songId to favorite
        inFlight++
        mostAtOnce = maxOf(mostAtOnce, inFlight)
        try {
            if (hold) {
                val at = gate.value
                gate.first { it != at }
            }
            return accepts
        } finally {
            inFlight--
        }
    }
}

/** The songs' sessions: each open is a file played as it is, named for the song and where it starts. */
private class TestSongSessions : SongSessions {
    override val current = MutableStateFlow<PlaybackStream?>(null)
    override val replaced = MutableSharedFlow<PlaybackStream>()
    val opened = mutableListOf<Pair<String, Long>>()
    val released = mutableListOf<String>()
    var openMs = 0L

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
        delay(openMs)
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
