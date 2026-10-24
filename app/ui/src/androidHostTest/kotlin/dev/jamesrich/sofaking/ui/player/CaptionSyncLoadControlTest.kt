package dev.jamesrich.sofaking.ui.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import dev.jamesrich.sofaking.data.BufferPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionSyncLoadControlTest {
    @Test
    fun `load control forwards the player-id overloads to the default implementation`() {
        val control = CaptionSyncLoadControl(CaptionSync())
        val player = PlayerId.UNSET
        // These hit LoadControl's throwing defaults if forwarding is missing.
        control.onPrepared(player)
        assertEquals(DefaultLoadControl().getBackBufferDurationUs(player), control.getBackBufferDurationUs(player))
        assertEquals(
            DefaultLoadControl().retainBackBufferFromKeyframe(player),
            control.retainBackBufferFromKeyframe(player),
        )
        assertNotNull(control.getAllocator(player))
        control.onStopped(player)
        control.onReleased(player)
    }

    @Test
    fun `playback starts once the chosen buffer is downloaded`() {
        val control = prepared(CaptionSync(), PlaybackTuning())
        assertFalse(control.shouldStartPlayback(parameters(bufferedMs = 900)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 1_000)))
    }

    @Test
    fun `a stall waits for the rebuffer amount`() {
        val control = prepared(CaptionSync(), PlaybackTuning())
        assertFalse(control.shouldStartPlayback(parameters(bufferedMs = 1_500, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 2_000, rebuffering = true)))
    }

    @Test
    fun `a caption lead waits for the lead and its margin`() {
        val control = prepared(CaptionSync().apply { advanceUs = 3_000_000L }, PlaybackTuning())
        assertFalse(control.shouldStartPlayback(parameters(bufferedMs = 5_000)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 5_500)))
    }

    @Test
    fun `the fastest start still waits for the default control's own threshold`() {
        val control = prepared(CaptionSync(), PlaybackTuning().apply { setBuffer(BufferPreference.FAST_START) })
        assertFalse(control.shouldStartPlayback(parameters(bufferedMs = 400)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 500)))
    }

    private fun prepared(sync: CaptionSync, tuning: PlaybackTuning) =
        CaptionSyncLoadControl(sync, tuning).apply { onPrepared(PlayerId.UNSET) }

    /** What the player asks with [bufferedMs] downloaded ahead of a live item at normal speed. */
    private fun parameters(bufferedMs: Long, rebuffering: Boolean = false): LoadControl.Parameters {
        val timeline =
            SinglePeriodTimeline(C.TIME_UNSET, false, true, false, null, MediaItem.Builder().setMediaId("live").build())
        return LoadControl.Parameters(
            PlayerId.UNSET,
            timeline,
            MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0L,
            bufferedMs * 1_000L,
            1f,
            true,
            rebuffering,
            C.TIME_UNSET,
            C.TIME_UNSET,
        )
    }
}
