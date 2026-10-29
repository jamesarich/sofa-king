package dev.jamesrich.sofaking.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.HLS_MIME_TYPE
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.TrailerDwell
import dev.jamesrich.sofaking.data.TrailerPhase
import dev.jamesrich.sofaking.ui.player.AudioTrack
import dev.jamesrich.sofaking.ui.player.CaptionCue
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.MediaSessionHandle
import dev.jamesrich.sofaking.ui.player.PlaybackState
import dev.jamesrich.sofaking.ui.player.PlayerError
import dev.jamesrich.sofaking.ui.player.PlayerErrorCodes
import dev.jamesrich.sofaking.ui.player.PlayerStatus
import dev.jamesrich.sofaking.ui.player.StreamInfo
import dev.jamesrich.sofaking.ui.player.StreamLivePlayer
import dev.jamesrich.sofaking.ui.player.VideoZoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrailerPlaybackTest {
    private val player = TrailerTestPlayer()
    private val sessions = TrailerTestSessions()
    private val dwell = TrailerDwell()
    private val trailer = TrailerPlayback(player, sessions, dwell)

    private fun TestScope.watching() {
        backgroundScope.launch { trailer.watch() }
        runCurrent()
    }

    private suspend fun TestScope.playing(): PlaybackStream {
        assertTrue(trailer.open("t1") { true })
        runCurrent()
        return assertNotNull(trailer.stream)
    }

    @Test
    fun `an opened trailer plays muted on the shared player`() = runTest {
        watching()

        val stream = playing()

        assertEquals(stream.url, player.currentStreamUrl)
        assertEquals(0f, player.volume)
        assertTrue(player.playWhenReady)
        assertEquals(PlaybackState.BUFFERING, player.status.value.state)
        assertEquals(TrailerPhase.PLAYING, dwell.phase)
        assertEquals(listOf("t1"), sessions.opened)
    }

    @Test
    fun `a trailer the server will not play is given up for the page`() = runTest {
        sessions.refuse = true

        assertEquals(false, trailer.open("t1") { true })

        assertNull(trailer.stream)
        assertNull(player.currentStreamUrl)
        assertEquals(TrailerPhase.DONE, dwell.phase)
    }

    @Test
    fun `a trailer whose open fails is given up for the page`() = runTest {
        sessions.fail = IOException("down")

        assertEquals(false, trailer.open("t1") { true })

        assertEquals(TrailerPhase.DONE, dwell.phase)
    }

    @Test
    fun `a trailer no longer wanted once its session is open is released and leaves the player alone`() = runTest {
        assertEquals(false, trailer.open("t1") { false })

        assertEquals(listOf("t1"), sessions.released)
        assertNull(sessions.current.value)
        assertNull(player.currentStreamUrl)
        assertEquals(TrailerPhase.RESTING, dwell.phase, "it may start again when it is wanted")
    }

    @Test
    fun `a trailer whose session was replaced while it opened releases only its own`() = runTest {
        sessions.replaceOnOpen = sessions.stream("movie")

        assertEquals(false, trailer.open("t1") { true })

        assertEquals("movie", sessions.current.value?.itemId, "the other session stays")
        assertNull(player.currentStreamUrl)
    }

    @Test
    fun `a real play taking the session ends the trailer without touching what the player holds`() = runTest {
        watching()
        playing()

        player.setStream(sessions.stream("movie"))
        sessions.current.value = sessions.stream("movie")
        runCurrent()

        assertNull(trailer.stream)
        assertEquals(TrailerPhase.DONE, dwell.phase)
        assertEquals("umovie", player.currentStreamUrl)
        assertEquals(emptyList(), sessions.released)
        assertEquals(PlaybackState.BUFFERING, player.status.value.state, "the movie's player was not stopped")
    }

    @Test
    fun `a session the server dropped ends the trailer and stops its picture`() = runTest {
        watching()
        playing()

        sessions.current.value = null
        runCurrent()

        assertNull(trailer.stream)
        assertEquals(PlaybackState.IDLE, player.status.value.state)
        assertEquals(TrailerPhase.DONE, dwell.phase)
    }

    @Test
    fun `a trailer that plays to its end releases its session and does not start again`() = runTest {
        watching()
        playing()

        player.status(PlaybackState.ENDED)
        runCurrent()

        assertNull(trailer.stream)
        assertEquals(listOf("t1"), sessions.released)
        assertEquals(TrailerPhase.DONE, dwell.phase)
    }

    @Test
    fun `a player error ends the trailer and releases its session`() = runTest {
        watching()
        playing()

        player.fail()
        runCurrent()

        assertNull(trailer.stream)
        assertEquals(listOf("t1"), sessions.released)
        assertEquals(TrailerPhase.DONE, dwell.phase)
    }

    @Test
    fun `the app leaving the front releases the session and the trailer rests again`() = runTest {
        watching()
        playing()

        trailer.stopForBackground(nowMs = 9_000)

        assertNull(trailer.stream)
        assertEquals(listOf("t1"), sessions.released)
        assertNull(sessions.current.value)
        assertEquals(PlaybackState.IDLE, player.status.value.state)
        assertEquals(TrailerPhase.RESTING, dwell.phase)
        assertEquals(3_000L, dwell.nextCheckMs(9_000))
    }

    @Test
    fun `leaving the page releases the trailer and leaving twice releases once`() = runTest {
        watching()
        playing()

        trailer.leave()
        trailer.leave()

        assertEquals(listOf("t1"), sessions.released)
        assertNull(trailer.stream)
    }

    @Test
    fun `leaving a page whose trailer never played releases nothing`() = runTest {
        trailer.leave()
        trailer.stopForBackground(nowMs = 1)

        assertEquals(emptyList(), sessions.released)
        assertNull(player.currentStreamUrl)
    }

    @Test
    fun `a trailer stopped after the player moved on leaves the other stream playing`() = runTest {
        watching()
        playing()
        player.setStream(sessions.stream("song"))
        player.prepare()

        trailer.leave()

        assertEquals("usong", player.currentStreamUrl)
        assertEquals(PlaybackState.BUFFERING, player.status.value.state)
    }

    @Test
    fun `a canceled open leaves no trailer behind`() = runTest {
        sessions.openFails = CancellationException("page left")

        val failure = runCatching { trailer.open("t1") { true } }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertNull(trailer.stream)
        assertNull(player.currentStreamUrl)
        assertEquals(TrailerPhase.RESTING, dwell.phase)
    }
}

/** The trailer's sessions: one preview at a time, held in [current] like the app's. */
private class TrailerTestSessions : TrailerSessions {
    override val current = MutableStateFlow<PlaybackStream?>(null)
    val opened = mutableListOf<String>()
    val released = mutableListOf<String>()

    /** True for a server that will not play the preview, or the app holds something. */
    var refuse = false
    var fail: Exception? = null
    var openFails: CancellationException? = null

    /** What takes the session between the open and the trailer's check, as a play can. */
    var replaceOnOpen: PlaybackStream? = null

    fun stream(itemId: String) = PlaybackStream(
        "",
        "s$itemId",
        "u$itemId",
        HLS_MIME_TYPE,
        epoch = 1,
        PlaybackIntent.PREVIEW,
        seekable = true,
        itemId = itemId,
        mode = Wire.VodMode.DIRECT,
        itemDurationMs = 90_000,
    )

    override suspend fun preview(itemId: String, profile: PlaybackProfile): PlaybackStream? {
        openFails?.let { throw it }
        fail?.let { throw it }
        if (refuse) return null
        opened += itemId
        val stream = stream(itemId)
        current.value = replaceOnOpen ?: stream
        return stream
    }

    override fun release(itemId: String) {
        released += itemId
        if (current.value?.itemId == itemId) current.value = null
    }
}

/** A player whose status the test moves. */
private class TrailerTestPlayer : StreamLivePlayer({ 0L }) {
    override var playWhenReady = false
    override var positionMs = 0L

    fun status(state: PlaybackState) {
        mutableStatus.value = PlayerStatus(state, isPlaying = state == PlaybackState.READY, playWhenReady)
    }

    fun fail() {
        mutableErrors.tryEmit(PlayerError(PlayerErrorCodes.UNSPECIFIED))
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
