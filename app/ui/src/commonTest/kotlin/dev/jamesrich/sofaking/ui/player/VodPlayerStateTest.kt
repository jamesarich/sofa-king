package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VodPlayerStateTest {
    /** Records what the keys asked of the stream, focus and the way out. */
    private class Controls : VodControls {
        val calls = mutableListOf<String>()
        override fun seek(targetMs: Long) {
            calls += "seek $targetMs"
        }
        override fun playPause() {
            calls += "playPause"
        }
        override fun retry() {
            calls += "retry"
        }
        override fun toggleCaptions() {
            calls += "captions"
        }
        override fun zoom() {
            calls += "zoom"
        }
        override fun focusButtons() {
            calls += "buttons"
        }
        override fun focusPicture() {
            calls += "picture"
        }
        override fun playNext() {
            calls += "next"
        }
        override fun exit() {
            calls += "exit"
        }
    }

    private val controls = Controls()

    private fun VodPlayerState.key(
        key: Key,
        type: KeyEventType,
        paused: Boolean = false,
        hasButtons: Boolean = true,
        tracksPickable: Boolean = false,
        repeatCount: Int? = null,
        nowMs: Long = 0L,
    ) = onKeyEvent(key, type, controls, paused, hasButtons, tracksPickable, repeatCount, nowMs)

    private fun VodPlayerState.down(
        key: Key,
        paused: Boolean = false,
        hasButtons: Boolean = true,
        tracksPickable: Boolean = false,
        nowMs: Long = 0L,
    ) = key(key, KeyEventType.KeyDown, paused, hasButtons, tracksPickable, nowMs = nowMs)

    private fun VodPlayerState.up(key: Key) = key(key, KeyEventType.KeyUp)

    private fun playing(positionMs: Long = 60_000L, durationMs: Long? = 600_000L) = VodPlayerState().also {
        it.positionMs = positionMs
        it.durationMs = durationMs
    }

    @Test
    fun `the player opens with the bar up and nothing open`() {
        val state = VodPlayerState()
        assertTrue(state.overlayVisible)
        assertFalse(state.tracksOpen)
        assertNull(state.scrubTarget)
        assertNull(state.next)
    }

    @Test
    fun `Left and Right build a scrub target that OK applies`() {
        val state = playing()
        state.overlayVisible = false
        assertTrue(state.down(Key.DirectionRight))
        assertTrue(state.down(Key.DirectionRight))
        assertEquals(120_000L, state.scrubTarget)
        // The bar stays down; the seek badge shows the scrub instead.
        assertFalse(state.overlayVisible)
        assertTrue(state.down(Key.DirectionLeft))
        assertEquals(110_000L, state.scrubTarget)

        state.down(Key.DirectionCenter)
        assertEquals(listOf("seek 110000"), controls.calls)
        assertNull(state.scrubTarget)
    }

    @Test
    fun `with the bar up a scrub keeps it up`() {
        val state = playing()
        val request = state.overlayRequest
        state.down(Key.DirectionRight)
        assertTrue(state.overlayVisible)
        assertTrue(state.overlayRequest > request)
    }

    @Test
    fun `media play and pause keys leave the bar down and confirm in the middle`() {
        val state = playing()
        state.overlayVisible = false
        state.down(Key.MediaPlayPause)
        assertEquals(listOf("playPause"), controls.calls)
        assertFalse(state.overlayVisible)
        assertEquals(TransportHint.PAUSE, state.transport)
        assertEquals(1, state.transportPulse)
        state.down(Key.MediaPlay, paused = true)
        assertEquals(TransportHint.PLAY, state.transport)
        assertEquals(2, state.transportPulse)
        state.down(Key.Spacebar, paused = false)
        assertEquals(3, state.transportPulse)
        assertFalse(state.overlayVisible)
    }

    @Test
    fun `OK pauses with the bar up and media keys keep a shown bar up`() {
        val state = playing()
        state.overlayVisible = false
        state.down(Key.DirectionCenter)
        assertTrue(state.overlayVisible)
        assertEquals(0, state.transportPulse)
        state.down(Key.MediaPlayPause, paused = true)
        assertTrue(state.overlayVisible)
        assertEquals(0, state.transportPulse)
        assertEquals(listOf("playPause", "playPause"), controls.calls)
    }

    @Test
    fun `a scrub applies once the presses rest`() {
        val state = playing()
        state.down(Key.DirectionRight, nowMs = 1_000L)
        assertNull(state.settledScrub(1_000L + VodScrub.SCRUB_SETTLE_MS - 1))
        assertEquals(90_000L, state.settledScrub(1_000L + VodScrub.SCRUB_SETTLE_MS))
        assertNull(state.scrubTarget)
        assertNull(state.settledScrub(10_000L))
    }

    @Test
    fun `a scrub stops at the ends of the item`() {
        val state = playing(positionMs = 5_000L)
        state.down(Key.DirectionLeft)
        assertEquals(0L, state.scrubTarget)
    }

    @Test
    fun `a failed item takes no scrub and OK retries it`() {
        val state = playing()
        state.failure = "SESSION_UNAVAILABLE"
        assertTrue(state.down(Key.DirectionRight))
        assertNull(state.scrubTarget)
        state.down(Key.DirectionCenter)
        assertEquals(listOf("retry"), controls.calls)
    }

    @Test
    fun `OK and the play keys pause and play and bring the bar up`() {
        val state = playing()
        state.overlayVisible = false
        state.down(Key.DirectionCenter)
        assertTrue(state.overlayVisible)
        state.down(Key.MediaPlay, paused = false)
        state.down(Key.MediaPause, paused = false)
        state.down(Key.MediaPause, paused = true)
        state.down(Key.MediaPlay, paused = true)
        assertEquals(listOf("playPause", "playPause", "playPause"), controls.calls)
    }

    @Test
    fun `a key acts on key-down and its key-up is taken without acting`() {
        val state = playing()
        assertTrue(state.up(Key.DirectionCenter))
        assertTrue(controls.calls.isEmpty())
        assertFalse(state.down(Key.A))
    }

    @Test
    fun `Info hides the bar while playing and Up shows it`() {
        val state = playing()
        state.down(Key.Info)
        assertFalse(state.overlayVisible)
        state.down(Key.DirectionUp)
        assertTrue(state.overlayVisible)
        state.down(Key.Info, paused = true)
        assertTrue(state.overlayVisible)
    }

    @Test
    fun `Down shows the bar and then moves into its buttons`() {
        val state = playing()
        state.overlayVisible = false
        state.down(Key.DirectionDown)
        assertTrue(state.overlayVisible)
        assertTrue(controls.calls.isEmpty())
        state.down(Key.DirectionDown)
        assertEquals(listOf("buttons"), controls.calls)

        val bare = playing()
        bare.down(Key.DirectionDown, hasButtons = false)
        assertEquals(1, controls.calls.size)
    }

    @Test
    fun `the focused row takes its keys and Up hands focus back on key-up`() {
        val state = playing()
        state.buttonsFocused = true
        assertFalse(state.down(Key.DirectionLeft))
        assertFalse(state.down(Key.DirectionCenter))
        assertNull(state.scrubTarget)
        assertTrue(state.down(Key.DirectionUp))
        assertTrue(controls.calls.isEmpty())
        assertTrue(state.up(Key.DirectionUp))
        assertEquals(listOf("picture"), controls.calls)
    }

    @Test
    fun `a media key on the focused row returns focus and acts on the picture`() {
        val state = playing()
        state.buttonsFocused = true
        state.down(Key.MediaPlayPause)
        assertEquals(listOf("picture", "playPause"), controls.calls)
    }

    @Test
    fun `the Up next card takes OK on key-up and leaves other keys`() {
        val state = playing()
        state.next = VodUpNext("Next episode")
        assertTrue(state.down(Key.DirectionCenter))
        assertTrue(controls.calls.isEmpty())
        assertTrue(state.up(Key.DirectionCenter))
        assertEquals(listOf("next"), controls.calls)
        assertFalse(state.down(Key.DirectionRight))
        assertNull(state.scrubTarget)
    }

    @Test
    fun `the Audio Track key opens the track card in place of the bar`() {
        val state = playing()
        state.down(Key.MediaAudioTrack, tracksPickable = true)
        assertTrue(state.tracksOpen)
        assertFalse(state.overlayVisible)
        // The card's rows take the keys.
        assertFalse(state.down(Key.DirectionDown))

        val none = playing()
        none.overlayVisible = false
        none.down(Key.MediaAudioTrack, tracksPickable = false)
        assertFalse(none.tracksOpen)
        assertTrue(none.overlayVisible)
    }

    @Test
    fun `CC Zoom and Stop reach the controls`() {
        val state = playing()
        state.down(Key.Captions)
        state.down(Key.C)
        state.down(Key.TvZoomMode)
        state.down(Key.MediaStop)
        assertEquals(listOf("captions", "captions", "zoom", "exit"), controls.calls)
    }

    @Test
    fun `Back returns focus closes the card and drops a scrub before it leaves`() {
        val state = playing()
        state.buttonsFocused = true
        assertFalse(state.back(controls))
        assertEquals(listOf("picture"), controls.calls)

        state.buttonsFocused = false
        state.openTracks()
        assertFalse(state.back(controls))
        assertFalse(state.tracksOpen)

        state.down(Key.DirectionRight)
        assertFalse(state.back(controls))
        assertNull(state.scrubTarget)
        assertNull(state.settledScrub(Long.MAX_VALUE))

        assertTrue(state.back(controls))
    }

    @Test
    fun `a tap toggles the bar`() {
        val state = playing()
        state.tap()
        assertFalse(state.overlayVisible)
        state.tap()
        assertTrue(state.overlayVisible)
    }

    @Test
    fun `playing clears a failure and the end of an opened item ends it`() {
        val state = playing(positionMs = 595_000L)
        state.failure = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
        state.onStatus(PlayerStatus(PlaybackState.READY), opened = true)
        assertNull(state.failure)
        state.onStatus(PlayerStatus(PlaybackState.ENDED), opened = false)
        assertFalse(state.ended)
        state.onStatus(PlayerStatus(PlaybackState.ENDED), opened = true)
        assertTrue(state.ended)
    }

    @Test
    fun `with auto skip an intro counts down and skips once`() {
        val state = playing()
        val intro = VodMarker(60_000L, 90_000L, VodMarkerKind.INTRO)
        state.inIntro(intro, autoSkip = true, nowMs = 0L)
        assertEquals(5, state.autoSkipSeconds)
        assertTrue(state.focusSkip)
        assertFalse(state.autoSkipDue(1_500L))
        assertEquals(4, state.autoSkipSeconds)
        assertTrue(state.autoSkipDue(5_000L))
        assertNull(state.autoSkipSeconds)
        // Offered once: going back into the same intro neither counts down nor raises the bar again.
        state.focusSkip = false
        state.inIntro(intro, autoSkip = true, nowMs = 10_000L)
        assertNull(state.autoSkipSeconds)
        assertFalse(state.focusSkip)
    }

    @Test
    fun `any key stops the intro countdown and leaves the button`() {
        val state = playing()
        state.inIntro(VodMarker(60_000L, 90_000L, VodMarkerKind.INTRO), autoSkip = true, nowMs = 0L)
        state.down(Key.DirectionDown)
        assertNull(state.autoSkipSeconds)
        assertFalse(state.autoSkipDue(6_000L))
    }

    @Test
    fun `without auto skip an intro waits for a press`() {
        val state = playing()
        state.inIntro(VodMarker(60_000L, 90_000L, VodMarkerKind.INTRO), nowMs = 0L)
        assertTrue(state.focusSkip)
        assertNull(state.autoSkipSeconds)
        assertFalse(state.autoSkipDue(10_000L))
    }

    @Test
    fun `an intro waits while the track card has the remote`() {
        val state = playing()
        val intro = VodMarker(60_000L, 90_000L, VodMarkerKind.INTRO)
        state.openTracks()
        state.inIntro(intro, autoSkip = true, nowMs = 0L)
        assertFalse(state.focusSkip)
        assertFalse(state.overlayVisible)
        assertNull(state.autoSkipSeconds)
        state.closeTracks()
        state.inIntro(intro, autoSkip = true, nowMs = 1_000L)
        assertTrue(state.focusSkip)
        assertEquals(5, state.autoSkipSeconds)
    }

    @Test
    fun `an end short of the item reopens it twice and then fails`() {
        val state = playing(positionMs = 120_000L)
        state.onStatus(PlayerStatus(PlaybackState.ENDED), opened = true)
        assertFalse(state.ended)
        assertEquals(1, state.reopenRequest)
        state.onStatus(PlayerStatus(PlaybackState.ENDED), opened = true)
        assertEquals(2, state.reopenRequest)
        state.onStatus(PlayerStatus(PlaybackState.ENDED), opened = true)
        assertEquals(2, state.reopenRequest)
        assertEquals(STREAM_ENDED_EARLY, state.failure)
        assertFalse(state.ended)
    }

    @Test
    fun `an end short of the item after playback moved on reopens it again`() {
        val state = playing(positionMs = 120_000L)
        repeat(2) { state.onStatus(PlayerStatus(PlaybackState.ENDED), opened = true) }
        state.positionMs = 180_000L
        state.onStatus(PlayerStatus(PlaybackState.ENDED), opened = true)
        assertEquals(3, state.reopenRequest)
        assertNull(state.failure)
    }

    @Test
    fun `an end within the tolerance or with no known length ends the item`() {
        val near = playing(positionMs = 600_000L - EARLY_END_MS)
        near.onStatus(PlayerStatus(PlaybackState.ENDED), opened = true)
        assertTrue(near.ended)
        val unknown = playing(positionMs = 1_000L, durationMs = null)
        unknown.onStatus(PlayerStatus(PlaybackState.ENDED), opened = true)
        assertTrue(unknown.ended)
        assertEquals(0, unknown.reopenRequest)
    }

    @Test
    fun `running into a break brings the bar up to focus Skip break`() {
        val state = playing()
        state.overlayVisible = false
        state.ranIntoBreak()
        assertTrue(state.overlayVisible)
        assertTrue(state.focusSkip)
    }

    @Test
    fun `an intro brings the bar up to focus Skip intro once`() {
        val state = playing()
        val intro = VodMarker(5_000, 55_000, VodMarkerKind.INTRO)
        state.overlayVisible = false
        state.inIntro(intro)
        assertTrue(state.overlayVisible)
        assertTrue(state.focusSkip)

        // Back in the same intro, after the bar left or a seek back into it, the viewer is left to watch it.
        state.focusSkip = false
        state.overlayVisible = false
        state.inIntro(intro)
        assertFalse(state.overlayVisible)
        assertFalse(state.focusSkip)
    }

    @Test
    fun `a drag on the slider shows where it would land until it ends`() {
        val state = playing()
        state.dragTo(300_000L)
        assertEquals(300_000L, state.scrubTarget)
        state.endScrub()
        assertNull(state.scrubTarget)
    }
}
