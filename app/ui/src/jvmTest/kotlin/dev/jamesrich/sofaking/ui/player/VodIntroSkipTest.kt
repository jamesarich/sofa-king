package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.SofaKingApi
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The library and recordings player as a remote drives it: an intro or a break brings up its skip
 * button, focused, and OK skips it.
 */
@OptIn(ExperimentalTestApi::class)
class VodIntroSkipTest {
    /** A file played as it is, whose position the test moves on as playback would. */
    private class FakeVodPlayer : LivePlayer {
        override val status: StateFlow<PlayerStatus> =
            MutableStateFlow(PlayerStatus(PlaybackState.READY, isPlaying = true))
        override val errors: SharedFlow<PlayerError> = MutableSharedFlow()
        override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList())
        override val captionSync = CaptionSync()
        override var currentStreamUrl: String? = null
        override val currentChannelId: String? get() = currentStreamUrl?.let { "" }
        override val hasItem: Boolean get() = currentStreamUrl != null
        override val bufferedMs = 0L
        override val supportsCaptionLead = false
        override val hasTextTrack = false
        override val audioTracks: StateFlow<List<AudioTrack>> = MutableStateFlow(emptyList())
        override var volume = 1f
        override var playWhenReady = true
        override val isSeekable: Boolean get() = currentStreamUrl != null
        override var positionMs = 0L
        override val durationMs: Long? = 1_500_000L
        val seeks = mutableListOf<Long>()

        override fun selectAudioTrack(id: String) = Unit
        override fun streamInfo() = StreamInfo()
        override fun setCaptionsEnabled(enabled: Boolean) = Unit
        override fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying?) {
            currentStreamUrl = stream.url
        }
        override fun replaceStream(stream: PlaybackStream) {
            currentStreamUrl = stream.url
        }
        override fun prepare() = Unit
        override fun stop() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            this.positionMs = positionMs
        }
        override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = object : MediaSessionHandle {
            override fun publish(nowPlaying: NowPlaying) = Unit
            override fun close() = Unit
        }

        @Composable override fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit) = Box(
            modifier,
        )
        override fun release() = Unit
    }

    /** A server that plays episode `e1` as it is and takes the renewals and the release. */
    private fun sessions(): PlaybackSessions {
        val engine = MockEngine { request ->
            val body = if (request.method == HttpMethod.Delete) {
                ""
            } else {
                """
                {"id":"v1","itemId":"e1","mode":"direct","startMs":0,"durationMs":1500000,"expiresAt":"2026-10-10T13:05:00Z",
                 "descriptor":{"protocol":"file","mimeType":"video/x-matroska","url":"/media/vod/v1/1/token/file","epoch":1}}
                """.trimIndent()
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PlaybackSessions().also {
            runBlocking { it.configure(SofaKingApi("http://server.test:8080", "token", engine)) }
        }
    }

    private fun ComposeUiTest.show(
        player: FakeVodPlayer,
        markers: List<VodMarker>,
        upNext: VodUpNext? = null,
        onPlayNext: () -> Unit = {
        },
    ) {
        val sessions = sessions()
        val before = player.currentStreamUrl
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VodPlayerScreen(
                        title = VodTitle("Chapter 1", markers = markers),
                        player = player,
                        sessions = sessions,
                        itemId = "e1",
                        open = { sessions.play("e1", player.playbackProfile) },
                        onPosition = { _, _ -> },
                        onStopped = { _, _ -> },
                        onExit = {},
                        upNext = { upNext },
                        onPlayNext = onPlayNext,
                    )
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { player.currentStreamUrl.let { it != null && it != before } }
    }

    private fun ComposeUiTest.awaitFocused(label: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithContentDescription(label).assertIsFocused()
    }

    @Test
    fun `an intro brings up Skip intro focused and OK jumps to its end`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val player = FakeVodPlayer()
            show(player, listOf(VodMarker(5_000, 55_000, VodMarkerKind.INTRO)))
            // Before the intro there is nothing to skip.
            player.positionMs = 2_000
            mainClock.advanceTimeBy(1_000)
            assertEquals(0, onAllNodes(hasContentDescription("Skip intro")).fetchSemanticsNodes().size)

            player.positionMs = 6_000
            awaitFocused("Skip intro")
            onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            waitForIdle()
            assertEquals(listOf(55_000L), player.seeks)
        }

    @Test
    fun `credits that run to the end bring up Next episode focused and OK plays it`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val player = FakeVodPlayer()
            var played = 0
            show(
                player,
                listOf(
                    VodMarker(1_440_000, 1_500_000, VodMarkerKind.CREDITS),
                ),
                upNext = VodUpNext("Chapter 2"),
                onPlayNext = {
                    played++
                },
            )
            // Playing up to the credits first, as an episode does.
            player.positionMs = 1_441_000 - 2_000
            mainClock.advanceTimeBy(1_000)
            player.positionMs = 1_441_000
            // The bar already offers Next episode at its end; the credits' own button takes its place at the front.
            mainClock.advanceTimeBy(1_000)
            awaitFocused("Next episode")
            onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            waitForIdle()
            assertEquals(1, played)
            assertEquals(emptyList(), player.seeks)
        }

    @Test
    fun `credits followed by more of the episode bring up Skip credits`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val player = FakeVodPlayer()
            show(
                player,
                listOf(VodMarker(1_300_000, 1_380_000, VodMarkerKind.CREDITS)),
                upNext = VodUpNext("Chapter 2"),
            )
            // Playing up to the credits first, as an episode does.
            player.positionMs = 1_301_000 - 2_000
            mainClock.advanceTimeBy(1_000)
            player.positionMs = 1_301_000
            awaitFocused("Skip credits")
            onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            waitForIdle()
            assertEquals(listOf(1_380_000L), player.seeks)
        }

    @Test
    fun `an item opened after another was left paused plays`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val player = FakeVodPlayer()
            player.currentStreamUrl = "http://server.test:8080/media/vod/v0/1/token/file"
            player.playWhenReady = false
            show(player, emptyList())
            waitForIdle()
            assertEquals(true, player.playWhenReady)
        }

    @Test
    fun `running into a break brings up Skip break focused`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        val player = FakeVodPlayer()
        show(player, listOf(VodMarker(60_000, 180_000, VodMarkerKind.BREAK)))
        player.positionMs = 59_800
        mainClock.advanceTimeBy(1_000)
        player.positionMs = 60_100
        awaitFocused("Skip break")
        onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()
        assertEquals(listOf(180_000L), player.seeks)
    }
}
