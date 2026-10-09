package dev.jamesrich.sofaking.ui.player

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CaptionSyncTest {
    @Test
    fun `no advance needs no extra buffer`() {
        assertEquals(0L, CaptionSync.bufferNeededToStartUs(0L))
        assertEquals(0L, CaptionSync.bufferNeededToStartUs(-1L))
    }

    @Test
    fun `an advance needs that much buffer plus the margin`() {
        assertEquals(5_500_000L, CaptionSync.bufferNeededToStartUs(3_000_000L))
    }

    @Test
    fun `look-ahead drops at once when the buffer dips below the advance`() {
        // Buffer 4.6 s with a 6 s advance: stay 0.75 s inside the download edge.
        assertEquals(3_850_000L, CaptionSync.nextEffectiveUs(6_000_000L, 6_000_000L, 4_600_000L))
    }

    @Test
    fun `look-ahead recovers in small steps and never exceeds the advance`() {
        assertEquals(4_000_000L, CaptionSync.nextEffectiveUs(3_900_000L, 6_000_000L, 9_000_000L))
        assertEquals(6_000_000L, CaptionSync.nextEffectiveUs(5_950_000L, 6_000_000L, 9_000_000L))
    }

    @Test
    fun `look-ahead is zero when captions are off or nothing is buffered`() {
        assertEquals(0L, CaptionSync.nextEffectiveUs(2_000_000L, 0L, 9_000_000L))
        assertEquals(0L, CaptionSync.nextEffectiveUs(2_000_000L, 6_000_000L, 0L))
    }

    @Test
    fun `load control forwards the player-id overloads to the default implementation`() {
        val control = CaptionSyncLoadControl(CaptionSync())
        val player = PlayerId.UNSET
        // These hit LoadControl's throwing defaults if forwarding is missing.
        control.onPrepared(player)
        assertEquals(DefaultLoadControl().getBackBufferDurationUs(player), control.getBackBufferDurationUs(player))
        assertEquals(DefaultLoadControl().retainBackBufferFromKeyframe(player), control.retainBackBufferFromKeyframe(player))
        assertNotNull(control.getAllocator(player))
        control.onStopped(player)
        control.onReleased(player)
    }
}
