package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RecordingGroupsTest {
    private fun recording(id: String, state: RecordingState, startsAt: String) = DvrRecording(
        id = id, channelId = "c1", channelName = "News 4", title = "Title", episodeTitle = null,
        description = null,
        season = null, episode = null, imageUrl = null,
        startsAt = Instant.parse(startsAt),
        endsAt = Instant.parse(startsAt),
        state = state, reason = null, bytes = 0, protected = false, positionMs = 0, watched = false,
        breaks = emptyList(),
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
            listOf(
                RecordingGroup.NOW to listOf("now"),
                RecordingGroup.RECORDED to listOf("failed", "old"),
                RecordingGroup.SCHEDULED to listOf("s1", "s2"),
            ),
            groups.map { (group, list) -> group to list.map { it.id } },
        )
        assertEquals(emptyList(), recordingGroups(emptyList()))
    }

    @Test
    fun `the Keep button steps from all through 1 3 5 and 10 and back to all`() {
        assertEquals(listOf(1, 3, 5, 10, 0), KEEP_STEPS.map(::nextKeep))
        assertEquals(5, nextKeep(4), "a count set on the portal goes to the next step above it")
        assertEquals(0, nextKeep(25))
    }
}
