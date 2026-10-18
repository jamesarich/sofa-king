package dev.jamesrich.sofaking.ui.recordings

import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.ui.TimeFormatter
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RecordingListTest {
    private fun recording(id: String, state: RecordingState, startsAt: String, season: Int? = null, episode: Int? = null, episodeTitle: String? = null) = DvrRecording(
        id = id, channelId = "c1", channelName = "News 4", title = "Title", episodeTitle = episodeTitle, description = null,
        season = season, episode = episode, imageUrl = null, startsAt = Instant.parse(startsAt), endsAt = Instant.parse(startsAt),
        state = state, reason = null, bytes = 0, protected = false, positionMs = 0, watched = false, breaks = emptyList(),
    )

    @Test
    fun `running recordings lead then recorded newest first then scheduled soonest first`() {
        val groups = recordingGroups(
            listOf(
                recording("s2", RecordingState.SCHEDULED, "2026-10-20T18:00:00Z"),
                recording("old", RecordingState.COMPLETED, "2026-10-01T18:00:00Z"),
                recording("s1", RecordingState.SCHEDULED, "2026-10-19T18:00:00Z"),
                recording("failed", RecordingState.FAILED, "2026-10-10T18:00:00Z"),
                recording("now", RecordingState.RECORDING, "2026-10-18T18:00:00Z"),
            ),
        )
        assertEquals(
            listOf(RecordingGroup.NOW to listOf("now"), RecordingGroup.RECORDED to listOf("failed", "old"), RecordingGroup.SCHEDULED to listOf("s1", "s2")),
            groups.map { (group, list) -> group to list.map { it.id } },
        )
        assertEquals(emptyList(), recordingGroups(emptyList()))
    }

    @Test
    fun `the subtitle names the episode then the channel and when it aired`() {
        val time = TimeFormatter(TimeZone.UTC)
        assertEquals(
            "S2 E5 · Pilot · News 4 · Sun Oct 18, 6:00 PM",
            recordingSubtitle(recording("r", RecordingState.COMPLETED, "2026-10-18T18:00:00Z", 2, 5, "Pilot"), time),
        )
        assertEquals("News 4 · Sun Oct 18, 6:00 PM", recordingSubtitle(recording("r", RecordingState.COMPLETED, "2026-10-18T18:00:00Z"), time))
    }

    @Test
    fun `sizes read in the largest unit with a decimal under ten`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("740 MB", formatBytes(740_000_000))
        assertEquals("2.4 GB", formatBytes(2_440_000_000))
        assertEquals("3 GB", formatBytes(3_000_000_000))
        assertEquals("12 TB", formatBytes(12_000_000_000_000))
    }

    @Test
    fun `the Keep button steps from all through 1 3 5 and 10 and back to all`() {
        assertEquals(listOf(1, 3, 5, 10, 0), KEEP_STEPS.map(::nextKeep))
        assertEquals(5, nextKeep(4), "a count set on the portal goes to the next step above it")
        assertEquals(0, nextKeep(25))
    }
}
