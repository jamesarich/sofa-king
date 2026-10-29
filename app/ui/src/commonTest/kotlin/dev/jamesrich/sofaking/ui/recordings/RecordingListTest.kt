package dev.jamesrich.sofaking.ui.recordings

import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.ui.TimeFormatter
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
