package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.CommercialBreak
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.RecordingSchedule
import dev.jamesrich.sofaking.data.RecordingSpace
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.data.SeriesRule
import dev.jamesrich.sofaking.data.SofaKingApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModelTest {
    private val fixture = ViewModelFixture()

    @AfterTest
    fun tearDown() = fixture.tearDown()

    private fun recording(id: String, state: String, extra: String = "") =
        """{"id":"$id","channelId":"c1","channelName":"News 4","title":"Evening """ +
            """News","startsAt":"2026-10-18T18:00:00Z",""" +
            """"endsAt":"2026-10-18T18:30:00Z","state":"$state"$extra}"""

    /** A recording server answering [handler]; every one has rules, so a test that does not answer them sees none. */
    private fun TestScope.recordingServer(handler: suspend (String, String, String) -> Pair<Int, String>?) =
        RouteServer(this) { method, path, body ->
            handler(method, path, body)
                ?: (200 to """{"rules":[]}""").takeIf { method == "GET" && path == "/api/v1/recording-rules" }
        }

    /** The list on [server], as the connection holds it for a server that records. */
    private fun TestScope.schedule(server: RouteServer, api: SofaKingApi? = server.http.api()) =
        RecordingSchedule(backgroundScope, MutableStateFlow(api), flowOf(true))

    /** The model on [schedule], stopped as the test ends. */
    private suspend fun TestScope.viewModel(
        server: RouteServer,
        schedule: RecordingSchedule = schedule(server),
    ): RecordingsViewModel = fixture.started(
        RecordingsViewModel(
            schedule,
            fixture.sessions(this, server.http.api()),
            timeSource = testScheduler.timeSource,
            saveInterval = 10.seconds,
        ),
    )

    private val tv = PlaybackProfile(listOf("mp4"), listOf("h264"), listOf("aac"))

    @Test
    fun `the list maps each recording with its breaks and the folder's space`() = fixture.viewModelTest {
        val server = recordingServer { method, path, _ ->
            if (method == "GET" && path == "/api/v1/recordings") {
                200 to """{"recordings":[
                ${recording(
                    "r1",
                    "completed",
                    ""","episodeTitle":"","bytes":2000,"positionMs":60000,"breaks":[{"startMs":900000,"endMs":1080000},{"startMs":30000,"endMs":90000},{"startMs":5,"endMs":5}]""",
                )},
                ${recording("r2", "archived")},
                {"id":"bad","channelId":"c1","title":"x","startsAt":"soon","endsAt":"later","state":"scheduled"}],
                "storage":{"freeBytes":10,"totalBytes":100,"usedBytes":5}}"""
            } else {
                null
            }
        }
        val model = viewModel(server)
        runCurrent()

        val ready = assertIs<RecordingsState.Ready>(model.state.value)
        assertEquals(RecordingSpace(10, 100), ready.space)
        assertEquals(
            listOf("r1", "r2"),
            ready.recordings.map {
                it.id
            },
            "a recording with unreadable times is left out",
        )
        val first = ready.recordings.first()
        assertEquals(null, first.episodeTitle)
        assertTrue(first.playable)
        assertTrue(first.inProgress)
        assertEquals(
            listOf(CommercialBreak(30_000, 90_000), CommercialBreak(900_000, 1_080_000)),
            first.breaks,
            "in order, empty ones dropped",
        )
        assertEquals(RecordingState.OTHER, ready.recordings.last().state)
        assertFalse(ready.recordings.last().playable)
    }

    @Test
    fun `series rules load with the list and change in place and stop and the guide follows`() = fixture.viewModelTest {
        var rules = """{"id":"s1","title":"Evening """ +
            """News","channelId":"c1","newOnly":true,"keep":0,"startPaddingSeconds":60,"endPaddingSeconds":120,"priority":0,"createdAt":"2026-10-18T00:00:00Z"}"""
        val server = recordingServer { method, path, body ->
            when {
                method == "GET" && path == "/api/v1/recordings" -> 200 to """{"recordings":[]}"""

                method == "GET" && path == "/api/v1/recording-rules" -> 200 to """{"rules":[$rules]}"""

                method == "PATCH" && path == "/api/v1/recording-rules/s1" -> {
                    rules = rules.replace("\"newOnly\":true", "\"newOnly\":false").replace("\"keep\":0", "\"keep\":3")
                    200 to rules
                }

                method == "DELETE" && path == "/api/v1/recording-rules/s1" -> {
                    rules = ""
                    204 to ""
                }

                else -> null
            }
        }
        val schedule = schedule(server)
        val model = viewModel(server, schedule)
        runCurrent()
        val news =
            Programme(
                "c1",
                Instant.parse("2026-10-18T18:00:00Z"),
                Instant.parse("2026-10-18T18:30:00Z"),
                "Evening News",
                null,
            )
        assertTrue(schedule.marks.value.of(news).series)

        val rule = assertIs<RecordingsState.Ready>(model.state.value).rules.single()
        assertEquals(SeriesRule("s1", "Evening News", "c1", newOnly = true, keep = 0), rule)
        assertTrue(model.updateRule(rule, newOnly = false, keep = 3))
        assertEquals(
            """PATCH /api/v1/recording-rules/s1 {"newOnly":false,"keep":3}""",
            server.requests.first {
                it.startsWith("PATCH")
            },
        )
        runCurrent()
        assertEquals(
            SeriesRule("s1", "Evening News", "c1", newOnly = false, keep = 3),
            assertIs<RecordingsState.Ready>(model.state.value).rules.single(),
        )

        assertTrue(model.deleteRule(rule))
        runCurrent()
        assertEquals(emptyList(), assertIs<RecordingsState.Ready>(model.state.value).rules)
        assertFalse(schedule.marks.value.of(news).series)
    }

    @Test
    fun `a server that does not record says so`() = fixture.viewModelTest {
        val model = viewModel(recordingServer { _, _, _ -> null })
        runCurrent()

        assertIs<RecordingsState.Unavailable>(model.state.value)
    }

    @Test
    fun `deleting a recording drops it`() = fixture.viewModelTest {
        var deleted = false
        val server = recordingServer { method, path, _ ->
            when {
                method == "DELETE" && path == "/api/v1/recordings/r1" -> {
                    deleted = true
                    204 to ""
                }

                method == "GET" ->
                    200 to
                        (
                            if (deleted) {
                                """{"recordings":[]}"""
                            } else {
                                """{"recordings":[${recording(
                                    "r1",
                                    "completed",
                                )}]}"""
                            }
                            )

                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        val recording = model.recording("r1")!!
        assertTrue(model.delete(recording))
        runCurrent()

        assertEquals(emptyList(), assertIs<RecordingsState.Ready>(model.state.value).recordings)
    }

    @Test
    fun `playing resumes where the household stopped and the end marks it watched`() = fixture.viewModelTest {
        val server = recordingServer { method, path, body ->
            when {
                method == "GET" && path == "/api/v1/recordings" ->
                    200 to """{"recordings":[${recording("r1", "completed", ""","positionMs":120000""")}]}"""

                method == "POST" && path == "/api/v1/vod-sessions" ->
                    201 to
                        """{"id":"v1","itemId":"","recordingId":"r1","mode":"remux","startMs":120000,""" +
                        """"durationMs":1800000,""" +
                        """"descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/vod/v1/1/k/index.m3u8","epoch":1}}"""

                method == "PATCH" && path == "/api/v1/recordings/r1" ->
                    200 to
                        recording("r1", "completed", if ("watched" in body) ""","watched":true""" else "")

                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        val stream = model.play(model.recording("r1")!!, tv)
        assertEquals("r1", stream.itemId)
        assertTrue(
            server.requests.any {
                it.startsWith("POST /api/v1/vod-sessions") && "\"startMs\":120000" in it &&
                    "\"recordingId\":\"r1\"" in it
            },
            server.requests.toString(),
        )

        model.progress("r1", 130_000, 1_800_000)
        advanceTimeBy(11.seconds)
        model.progress("r1", 141_000, 1_800_000)
        runCurrent()
        assertEquals("""PATCH /api/v1/recordings/r1 {"positionMs":141000}""", server.requests.last())

        model.stopped("r1", 1_700_000, 1_800_000)
        runCurrent()
        assertEquals("""PATCH /api/v1/recordings/r1 {"positionMs":0,"watched":true}""", server.requests.last())
        assertTrue(model.recording("r1")!!.watched)
    }
}
