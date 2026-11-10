package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.SofaKingException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PanePlayTest {
    private class FakePlayer :
        LivePlayer,
        PlayerTracks by NoTracks {
        override val captionSync = CaptionSync()
        override val status = MutableStateFlow(PlayerStatus())
        override val errors = MutableSharedFlow<PlayerError>(extraBufferCapacity = 8)
        var stream: PlaybackStream? = null
        var prepares = 0
        var stops = 0
        override val currentChannelId: String? get() = stream?.channelId
        override val currentStreamUrl: String? get() = stream?.url
        override val hasItem: Boolean get() = stream != null
        override val bufferedMs: Long = 0L
        override val supportsCaptionLead: Boolean = false
        override var volume: Float = 1f
        override var playWhenReady: Boolean = true

        override fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying?) {
            this.stream = stream
        }

        override fun replaceStream(stream: PlaybackStream) {
            this.stream = stream
        }

        override fun prepare() {
            prepares++
        }

        override fun stop() {
            stops++
            status.value = PlayerStatus()
        }

        override fun play() = Unit

        override fun pause() = Unit

        override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = NoMediaSession

        @Composable
        override fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit) = Unit

        override fun release() = Unit

        fun playing() {
            status.value = PlayerStatus(PlaybackState.READY, isPlaying = true)
        }
    }

    private class FakeSessions : PaneSessions {
        override val replaced = MutableSharedFlow<PlaybackStream>(extraBufferCapacity = 4)
        val tunes = mutableListOf<String>()
        var releases = 0
        var failWith: Exception? = null

        override suspend fun tune(channelId: String): PlaybackStream {
            tunes += channelId
            failWith?.let { throw it }
            return stream(channelId, epoch = tunes.size)
        }

        override suspend fun release() {
            releases++
        }
    }

    @Test
    fun `a pane tunes its channel and plays it`() = runTest {
        val player = FakePlayer()
        val sessions = FakeSessions()
        val pane = PanePlay(player, sessions)
        backgroundScope.launch { pane.play("c1") }
        runCurrent()
        assertEquals(listOf("c1"), sessions.tunes)
        assertEquals("c1", player.currentChannelId)
        assertEquals(1, player.prepares)
        assertTrue(pane.opening)

        player.playing()
        runCurrent()
        assertFalse(pane.opening)
        assertNull(pane.failure)
    }

    @Test
    fun `a stream that fails is tuned again after the backoff and one that ends too`() = runTest {
        val player = FakePlayer()
        val sessions = FakeSessions()
        val pane = PanePlay(player, sessions)
        backgroundScope.launch { pane.play("c1") }
        runCurrent()

        player.errors.tryEmit(PlayerError(PlayerErrorCodes.NETWORK_CONNECTION_FAILED))
        runCurrent()
        assertEquals(PlayerErrorCodes.NETWORK_CONNECTION_FAILED, pane.failure)
        assertEquals(1, sessions.tunes.size, "the retry waits")
        advanceTimeBy(2_100)
        assertEquals(2, sessions.tunes.size)
        assertEquals(2, player.prepares)

        player.playing()
        runCurrent()
        assertNull(pane.failure)
        player.status.value = PlayerStatus(PlaybackState.ENDED)
        runCurrent()
        assertEquals(STREAM_ENDED, pane.failure)
        advanceTimeBy(2_100)
        assertEquals(3, sessions.tunes.size, "playing reset the backoff to its first step")
    }

    @Test
    fun `a stream the relay says is gone is read again at once`() = runTest {
        val player = FakePlayer()
        val sessions = FakeSessions()
        backgroundScope.launch { PanePlay(player, sessions).play("c1") }
        runCurrent()
        player.errors.tryEmit(PlayerError(PlayerErrorCodes.BAD_HTTP_STATUS, streamGone = true))
        runCurrent()
        assertEquals(2, sessions.tunes.size)
    }

    @Test
    fun `a session that cannot be opened is tried again but a channel the server lost is not`() = runTest {
        val player = FakePlayer()
        val sessions = FakeSessions().apply { failWith = apiFailure(Wire.ErrorCode.CAPACITY_EXHAUSTED) }
        val pane = PanePlay(player, sessions)
        backgroundScope.launch { pane.play("c1") }
        runCurrent()
        assertEquals(Wire.ErrorCode.CAPACITY_EXHAUSTED, pane.failure)
        assertEquals(0, player.prepares)

        sessions.failWith = apiFailure(Wire.ErrorCode.CHANNEL_NOT_FOUND)
        advanceTimeBy(2_100)
        assertEquals(2, sessions.tunes.size)
        advanceTimeBy(120_000)
        assertEquals(2, sessions.tunes.size, "a dead channel id is never retried")
        assertEquals(Wire.ErrorCode.CHANNEL_NOT_FOUND, pane.failure)
    }

    @Test
    fun `a stream the server replaced is loaded at its new address`() = runTest {
        val player = FakePlayer()
        val sessions = FakeSessions()
        backgroundScope.launch { PanePlay(player, sessions).play("c1") }
        runCurrent()
        val replaced = stream("c1", epoch = 9)
        sessions.replaced.tryEmit(replaced)
        runCurrent()
        assertEquals(replaced.url, player.currentStreamUrl)
        assertEquals(2, player.prepares)
        assertEquals(1, sessions.tunes.size, "no new session")
    }

    @Test
    fun `leaving stops the player and releases the session before the next channel tunes`() = runTest {
        val player = FakePlayer()
        val sessions = FakeSessions()
        val pane = PanePlay(player, sessions)
        val first = backgroundScope.launch { pane.play("c1") }
        runCurrent()
        val stops = player.stops
        first.cancelAndJoin()
        assertEquals(1, sessions.releases)
        assertTrue(player.stops > stops)

        backgroundScope.launch { pane.play("c2") }
        runCurrent()
        assertEquals(listOf("c1", "c2"), sessions.tunes)
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503

        fun stream(channelId: String, epoch: Int) = PlaybackStream(
            channelId,
            "s-$channelId",
            "http://server/media/sessions/s-$channelId/$epoch/root.m3u8",
            "application/vnd.apple.mpegurl",
            epoch,
            PlaybackIntent.WATCH,
        )

        fun apiFailure(code: String) = SofaKingException(SERVICE_UNAVAILABLE, code, code)
    }
}
