package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jamesrich.sofaking.data.HLS_MIME_TYPE
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamLivePlayerTest {
    private var now = 0L
    private val player = RecordingPlayer { now }

    private fun stream(url: String, seekable: Boolean = false) =
        PlaybackStream("c1", sessionId = "s1", url, HLS_MIME_TYPE, epoch = 1, PlaybackIntent.WATCH, seekable = seekable)

    @Test
    fun `a short pause resumes the held stream without reopening it`() {
        player.setStream(stream("live"))
        player.pause()
        now += 5_000L
        player.play()
        assertEquals(listOf("playWhenReady false", "playWhenReady true"), player.events)
    }

    @Test
    fun `a long pause on a live stream reopens it at the edge`() {
        player.setStream(stream("live"))
        player.pause()
        now += LIVE_RESUME_AFTER_MS + 1
        player.play()
        assertEquals(listOf("playWhenReady false", "playWhenReady true", "stop", "prepare live"), player.events)
    }

    @Test
    fun `a long pause on a seekable item resumes where it paused`() {
        player.setStream(stream("recording", seekable = true))
        player.pause()
        now += LIVE_RESUME_AFTER_MS + 1
        player.play()
        assertEquals(listOf("playWhenReady false", "playWhenReady true"), player.events)
    }

    @Test
    fun `replacing the stream stops the old one and keeps the new one for the next prepare`() {
        assertFalse(player.hasItem)
        player.setStream(stream("first"))
        player.replaceStream(stream("second"))
        player.prepare()
        assertTrue(player.hasItem)
        assertEquals("second", player.currentStreamUrl)
        assertEquals("c1", player.currentChannelId)
        assertEquals(listOf("stop", "prepare second"), player.events)
    }

    @Test
    fun `a seek stays within the item`() {
        assertEquals(0L, clampSeek(-500L, durationMs = 60_000L))
        assertEquals(60_000L, clampSeek(90_000L, durationMs = 60_000L))
        assertEquals(30_000L, clampSeek(30_000L, durationMs = 60_000L))
        assertEquals(90_000L, clampSeek(90_000L, durationMs = null))
        assertEquals(0L, clampSeek(-1L, durationMs = null))
    }
}

/** Records what the shared base asks of its engine. */
private class RecordingPlayer(nowMs: () -> Long) : StreamLivePlayer(nowMs) {
    val events = mutableListOf<String>()

    override var playWhenReady: Boolean = true
        set(value) {
            field = value
            events += "playWhenReady $value"
        }

    override fun prepare() {
        events += "prepare ${stream?.url}"
    }

    override fun stop() {
        events += "stop"
    }

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
    override fun Video(modifier: Modifier, shutter: @Composable () -> Unit) = Unit

    override fun release() = Unit
}
