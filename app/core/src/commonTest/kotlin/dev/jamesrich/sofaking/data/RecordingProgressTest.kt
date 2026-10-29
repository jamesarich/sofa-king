package dev.jamesrich.sofaking.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingProgressTest {
    private val start = Instant.parse("2026-10-18T18:00:00Z")
    private val end = Instant.parse("2026-10-18T19:00:00Z")

    private fun recording(
        state: RecordingState = RecordingState.RECORDING,
        positionMs: Long = 0,
        watched: Boolean = false,
    ) = DvrRecording(
        id = "r1", channelId = "c1", channelName = "News 4", title = "Evening News", episodeTitle = null,
        description = null, season = null, episode = null, imageUrl = null, startsAt = start, endsAt = end,
        state = state, reason = null, bytes = 0, protected = false, positionMs = positionMs, watched = watched,
        breaks = emptyList(),
    )

    @Test
    fun `the recorded fraction is zero before the start`() {
        assertEquals(0f, recordedFraction(start, end, Instant.parse("2026-10-18T17:00:00Z")))
    }

    @Test
    fun `the recorded fraction grows through the window`() {
        assertEquals(0.5f, recordedFraction(start, end, Instant.parse("2026-10-18T18:30:00Z")))
    }

    @Test
    fun `the recorded fraction stops at one after the end`() {
        assertEquals(1f, recordedFraction(start, end, Instant.parse("2026-10-18T21:00:00Z")))
    }

    @Test
    fun `a window with no length has no recorded fraction`() {
        assertNull(recordedFraction(start, start, start))
    }

    @Test
    fun `only a running recording reports how much is recorded`() {
        val now = Instant.parse("2026-10-18T18:15:00Z")
        assertEquals(0.25f, recording().recordedSoFar(now))
        assertNull(recording(RecordingState.COMPLETED).recordedSoFar(now))
        assertNull(recording(RecordingState.SCHEDULED).recordedSoFar(now))
    }

    @Test
    fun `the resume fraction is the position against the program length`() {
        assertEquals(0.5f, recording(RecordingState.COMPLETED, positionMs = 1_800_000).resumeFraction)
    }

    @Test
    fun `the resume fraction is clamped to one`() {
        assertEquals(1f, recording(RecordingState.COMPLETED, positionMs = 9_000_000).resumeFraction)
    }

    @Test
    fun `an unstarted or watched recording has no resume fraction`() {
        assertNull(recording(RecordingState.COMPLETED).resumeFraction)
        assertNull(recording(RecordingState.COMPLETED, positionMs = 60_000, watched = true).resumeFraction)
    }

    @Test
    fun `restoring a canceled recording asks for its program first and its window second`() {
        assertEquals(
            listOf(
                ScheduleRecordingDto("c1", "2026-10-18T18:00:00Z"),
                ScheduleRecordingDto("c1", "2026-10-18T18:00:00Z", "2026-10-18T19:00:00Z", "Evening News"),
            ),
            restoreRequests(recording(RecordingState.SCHEDULED)),
        )
    }

    @Test
    fun `restore falls back to the window when the guide has no such program`() = runTest {
        val http = FakeHttp(this) { seen ->
            when {
                seen.method == "POST" && !seen.body.contains("endsAt") -> apiError(404, "PROGRAM_NOT_FOUND")

                seen.method == "POST" -> json(
                    """{"id":"r2","channelId":"c1","title":"Evening News","startsAt":"2026-10-18T18:00:00Z",""" +
                        """"endsAt":"2026-10-18T19:00:00Z","state":"scheduled"}""",
                    201,
                )

                else -> json("{}")
            }
        }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertTrue(schedule.restore(recording(RecordingState.SCHEDULED)))
        assertEquals(2, http.requests.count { it.method == "POST" })
    }

    @Test
    fun `restore reports a refusal of both requests`() = runTest {
        val http = FakeHttp(this) { seen ->
            if (seen.method == "POST") apiError(409, "PROGRAM_ENDED") else json("{}")
        }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertFalse(schedule.restore(recording(RecordingState.SCHEDULED)))
    }
}
