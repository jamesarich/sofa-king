package dev.jamesrich.sofaking.ui.player

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CaptionSyncLoadControlTest {
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
