package dev.jamesrich.sofaking.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class RecordingScheduleTest {
    private val news = Programme("c1", Instant.parse("2026-10-18T18:00:00Z"), Instant.parse("2026-10-18T18:30:00Z"), "Evening News", null)
    private val film = Programme("c1", Instant.parse("2026-10-18T20:00:00Z"), Instant.parse("2026-10-18T22:00:00Z"), "Film", null)

    private val recordings = """{"recordings":[
        {"id":"r1","channelId":"c1","title":"Evening News","startsAt":"2026-10-18T18:00:00Z","endsAt":"2026-10-18T18:30:00Z","state":"scheduled"},
        {"id":"r0","channelId":"c1","title":"Film","startsAt":"2026-10-18T20:00:00Z","endsAt":"2026-10-18T22:00:00Z","state":"failed"}
    ]}"""

    @Test
    fun `a scheduled recording and a series rule mark the programmes they take`() = runTest {
        val http = FakeHttp(this) { seen ->
            when (seen.path) {
                "/api/v1/recordings" -> json(recordings)
                else -> json("""{"rules":[{"id":"rule1","title":" evening news "}]}""")
            }
        }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertEquals(RecordingMark(recording = true, series = true), schedule.marks.value.of(news))
        assertEquals(RecordingMark(), schedule.marks.value.of(film), "a failed recording is not set to record")
        assertEquals(RecordingMark(series = true), schedule.marks.value.of(news.copy(channelId = "c2")), "a rule for any channel")
    }

    @Test
    fun `record schedules the programme and a second press cancels it`() = runTest {
        var scheduled = false
        val http = FakeHttp(this) { seen ->
            when {
                seen.method == "POST" -> { scheduled = true; json("""{"id":"r9","channelId":"c1","title":"Film","startsAt":"2026-10-18T20:00:00Z","endsAt":"2026-10-18T22:00:00Z","state":"scheduled"}""", 201) }
                seen.method == "DELETE" -> { scheduled = false; json("", 204) }
                seen.path == "/api/v1/recordings" -> json(if (scheduled) """{"recordings":[{"id":"r9","channelId":"c1","title":"Film","startsAt":"2026-10-18T20:00:00Z","endsAt":"2026-10-18T22:00:00Z","state":"scheduled"}]}""" else "{}")
                else -> json("{}")
            }
        }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertTrue(schedule.toggle(film))
        assertTrue(schedule.marks.value.of(film).recording)
        assertTrue(schedule.toggle(film))
        assertFalse(schedule.marks.value.of(film).recording)
        assertEquals(
            listOf("""POST /api/v1/recordings {"channelId":"c1","startsAt":"2026-10-18T20:00:00Z"}""", "DELETE /api/v1/recordings/r9"),
            http.requests.filter { it.method != "GET" }.map { it.toString() },
        )
    }

    @Test
    fun `record series adds a rule for the title on the channel and a refusal says so`() = runTest {
        val http = FakeHttp(this) { seen ->
            if (seen.method == "POST") apiError(400, "INVALID_REQUEST") else json("{}")
        }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertFalse(schedule.toggleSeries(news))
        assertEquals("""POST /api/v1/recording-rules {"title":"Evening News","channelId":"c1"}""", http.requestsOfKind("POST").single())
    }

    @Test
    fun `a server that does not record is never asked`() = runTest {
        val http = FakeHttp(this) { json("{}") }
        val enabled = MutableStateFlow(false)
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), enabled)
        runCurrent()

        assertFalse(schedule.toggle(news))
        assertTrue(http.requests.isEmpty())
        enabled.value = true
        runCurrent()
        assertEquals(listOf("GET /api/v1/recordings", "GET /api/v1/recording-rules"), http.requests.map { it.toString() })
    }
}
