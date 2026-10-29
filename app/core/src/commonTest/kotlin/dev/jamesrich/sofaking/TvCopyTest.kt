package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.RecordingFailure
import dev.jamesrich.sofaking.data.RecordingGroup
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.data.SeriesRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvCopyTest {
    private fun rule(newOnly: Boolean, keep: Int, channelId: String?) =
        SeriesRule(id = "r", title = "News", channelId = channelId, newOnly = newOnly, keep = keep)

    @Test
    fun `arguments fill their numbered places`() {
        assertEquals("10 GB free of 200 GB", TvRecordingCopy.space("10 GB", "200 GB"))
        assertEquals("Recording until 6:00 PM", TvRecordingCopy.until("6:00 PM"))
        assertEquals("Too many tries; wait 30 seconds.", TvCopy.lockWait(30))
        assertEquals("News 4 at 192.168.1.10", TvCopy.discoveredServer("News 4", "192.168.1.10"))
    }

    @Test
    fun `a failure with no reason reads as plain failed`() {
        assertEquals("Failed", TvRecordingCopy.failureLabel(null))
        assertEquals("Failed", TvRecordingCopy.failureLabel(RecordingFailure.STOPPED))
        assertEquals(
            "Failed: the channel left the lineup",
            TvRecordingCopy.failureLabel(RecordingFailure.CHANNEL_GONE),
        )
    }

    @Test
    fun `an early end names its reason and a lost source and no media read alike`() {
        assertEquals("Stopped early", TvRecordingCopy.endedEarlyLabel(RecordingFailure.STOPPED))
        assertEquals(
            TvRecordingCopy.endedEarlyLabel(RecordingFailure.SOURCE),
            TvRecordingCopy.endedEarlyLabel(RecordingFailure.NO_MEDIA),
        )
        assertEquals("Ended early", TvRecordingCopy.endedEarlyLabel(RecordingFailure.OTHER))
    }

    @Test
    fun `ending a recording is named by what it is doing`() {
        assertEquals("Stop", TvRecordingCopy.deleteLabel(RecordingState.RECORDING))
        assertEquals("Cancel", TvRecordingCopy.deleteLabel(RecordingState.SCHEDULED))
        assertEquals("Delete", TvRecordingCopy.deleteLabel(RecordingState.COMPLETED))
        assertEquals("Recording now", TvRecordingCopy.groupTitle(RecordingGroup.NOW))
    }

    @Test
    fun `a series rule reads as one line and its two buttons`() {
        assertEquals("New episodes only · Keeps 5 · Any channel", TvRecordingCopy.summary(rule(true, 5, null)))
        assertEquals("All airings · Keeps all", TvRecordingCopy.summary(rule(false, 0, "c1")))
        assertEquals("Record repeats too", TvRecordingCopy.takeLabel(rule(true, 5, null)))
        assertEquals("Keep: All", TvRecordingCopy.keepLabel(rule(true, 0, null)))
        assertEquals("Keep: 5", TvRecordingCopy.keepLabel(rule(true, 5, null)))
    }

    @Test
    fun `the lock footer follows whether a PIN and kids mode are on`() {
        assertEquals(TvCopy.lockHelpFooter, TvCopy.lockFooter(locked = false, kidsMode = false))
        assertEquals(TvCopy.kidsOn, TvCopy.lockFooter(locked = true, kidsMode = true))
        assertTrue("Kids mode shows only" in TvCopy.lockFooter(locked = true, kidsMode = false))
    }

    @Test
    fun `every pairing failure has its own words`() {
        val words = PairingFailure.entries.map { TvCopy.pairingFailure(it) }
        assertEquals(PairingFailure.entries.size, words.toSet().size)
    }
}
