package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class RecordingFailureTest {
    private fun recording(state: RecordingState, reason: String?) = DvrRecording(
        id = "r1",
        channelId = "c1",
        channelName = "News 4",
        title = "Evening News",
        episodeTitle = null,
        description = null,
        season = null,
        episode = null,
        imageUrl = null,
        startsAt = Instant.fromEpochSeconds(0),
        endsAt = Instant.fromEpochSeconds(1800),
        state = state,
        reason = reason,
        bytes = 0,
        protected = false,
        positionMs = 0,
        watched = false,
        breaks = emptyList(),
    )

    @Test
    fun `each server reason code maps to its failure`() {
        assertEquals(RecordingFailure.MISSED, RecordingFailure.of("MISSED"))
        assertEquals(RecordingFailure.DISK_FULL, RecordingFailure.of("DISK_FULL"))
        assertEquals(RecordingFailure.NO_MEDIA, RecordingFailure.of("NO_MEDIA"))
        assertEquals(RecordingFailure.SOURCE, RecordingFailure.of("SOURCE_UNAVAILABLE"))
        assertEquals(RecordingFailure.SOURCE, RecordingFailure.of("SESSION_ENDED"))
        assertEquals(RecordingFailure.SOURCE, RecordingFailure.of("NO_SOURCES"))
        assertEquals(RecordingFailure.CHANNEL_GONE, RecordingFailure.of("CHANNEL_NOT_FOUND"))
    }

    @Test
    fun `an unknown or missing code is OTHER`() {
        assertEquals(RecordingFailure.OTHER, RecordingFailure.of("IOException"))
        assertEquals(RecordingFailure.OTHER, RecordingFailure.of(null))
    }

    @Test
    fun `only a failed recording has a failure`() {
        assertEquals(RecordingFailure.DISK_FULL, recording(RecordingState.FAILED, "DISK_FULL").failure)
        assertNull(recording(RecordingState.COMPLETED, "DISK_FULL").failure)
    }

    @Test
    fun `a kept recording with a reason ended early and one without ran to the end`() {
        assertEquals(RecordingFailure.STOPPED, recording(RecordingState.COMPLETED, "STOPPED").endedEarly)
        assertEquals(RecordingFailure.DISK_FULL, recording(RecordingState.COMPLETED, "DISK_FULL").endedEarly)
        assertNull(recording(RecordingState.COMPLETED, null).endedEarly)
        assertNull(recording(RecordingState.FAILED, "DISK_FULL").endedEarly)
    }
}
