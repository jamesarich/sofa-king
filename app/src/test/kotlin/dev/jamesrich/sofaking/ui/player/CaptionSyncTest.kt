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
        assertEquals(3_500_000L, CaptionSync.bufferNeededToStartUs(3_000_000L))
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
