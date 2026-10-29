package dev.jamesrich.sofaking.ui.recordings

import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingPadding
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.ui.TimeFormatter
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class RecordingListTest {
    private fun recording(
        id: String,
        state: RecordingState,
        startsAt: String,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null,
    ) = DvrRecording(
        id = id, channelId = "c1", channelName = "News 4", title = "Title", episodeTitle = episodeTitle,
        description = null,
        season = season, episode = episode, imageUrl = null,
        startsAt = Instant.parse(
            startsAt,
        ),
        endsAt = Instant.parse(startsAt),
        state = state, reason = null, bytes = 0, protected = false, positionMs = 0, watched = false,
        breaks = emptyList(),
    )

    @Test
    fun `the subtitle names the episode then the channel and when it aired`() {
        val time = TimeFormatter(TimeZone.UTC)
        assertEquals(
            "S2 E5 · Pilot · News 4 · Sun Oct 18, 6:00 PM",
            recordingSubtitle(recording("r", RecordingState.COMPLETED, "2026-10-18T18:00:00Z", 2, 5, "Pilot"), time),
        )
        assertEquals(
            "News 4 · Sun Oct 18, 6:00 PM",
            recordingSubtitle(recording("r", RecordingState.COMPLETED, "2026-10-18T18:00:00Z"), time),
        )
    }

    @Test
    fun `the Padding button steps through the choices and sends a portal value to the default`() {
        assertEquals(RecordingPadding.NONE, nextPadding(RecordingPadding.DEFAULT))
        assertEquals(RecordingPadding.DEFAULT, nextPadding(RecordingPadding.OVERRUN))
        assertEquals(RecordingPadding.DEFAULT, nextPadding(null))
    }

    @Test
    fun `only a scheduled recording in conflict will not record`() {
        assertTrue(recording("a", RecordingState.SCHEDULED, "2026-10-18T18:00:00Z").copy(conflict = true).wontRecord)
        assertFalse(recording("b", RecordingState.SCHEDULED, "2026-10-18T18:00:00Z").wontRecord)
        assertFalse(recording("c", RecordingState.COMPLETED, "2026-10-18T18:00:00Z").copy(conflict = true).wontRecord)
    }

    @Test
    fun `only a finished recording can be protected`() {
        assertTrue(recording("a", RecordingState.COMPLETED, "2026-10-18T18:00:00Z").canProtect)
        assertFalse(recording("b", RecordingState.SCHEDULED, "2026-10-18T18:00:00Z").canProtect)
        assertFalse(recording("c", RecordingState.RECORDING, "2026-10-18T18:00:00Z").canProtect)
    }

    @Test
    fun `a handheld cancels a scheduled recording at once and offers undo`() {
        assertTrue(undoesInsteadOfConfirming(tv = false, state = RecordingState.SCHEDULED))
    }

    @Test
    fun `a TV keeps the question for a scheduled recording`() {
        assertFalse(undoesInsteadOfConfirming(tv = true, state = RecordingState.SCHEDULED))
    }

    @Test
    fun `stopping and deleting ask first on every device`() {
        for (tv in listOf(true, false)) {
            assertFalse(undoesInsteadOfConfirming(tv, RecordingState.RECORDING))
            assertFalse(undoesInsteadOfConfirming(tv, RecordingState.COMPLETED))
            assertFalse(undoesInsteadOfConfirming(tv, RecordingState.FAILED))
        }
    }
}
