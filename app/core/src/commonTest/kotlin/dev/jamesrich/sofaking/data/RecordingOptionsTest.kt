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
class RecordingOptionsTest {
    private val news =
        Program(
            "c1",
            Instant.parse("2026-10-18T18:00:00Z"),
            Instant.parse("2026-10-18T18:30:00Z"),
            "Evening News",
            null,
        )

    private val createdRule = """{"id":"s2","title":"Evening News"}"""
    private val createdRecording =
        """{"id":"w1","channelId":"c9","title":"Channel","startsAt":"2026-10-18T18:00:00Z",""" +
            """"endsAt":"2026-10-18T19:00:00Z","state":"scheduled"}"""

    @Test
    fun `padding steps through every choice and back to the default`() {
        var padding = RecordingPadding.DEFAULT
        val seen = buildList {
            repeat(RecordingPadding.entries.size) {
                padding = padding.next()
                add(padding)
            }
        }
        assertEquals(RecordingPadding.entries.drop(1) + RecordingPadding.DEFAULT, seen)
    }

    @Test
    fun `a padding finds its choice by seconds and a portal value finds none`() {
        assertEquals(RecordingPadding.DEFAULT, RecordingPadding.of(60, 120))
        assertEquals(RecordingPadding.NONE, RecordingPadding.of(0, 0))
        assertNull(RecordingPadding.of(61, 120))
    }

    @Test
    fun `the overrun padding runs half an hour past the end`() {
        assertEquals(60, RecordingPadding.OVERRUN.startSeconds)
        assertEquals(30 * 60, RecordingPadding.OVERRUN.endSeconds)
    }

    @Test
    fun `a keyword is trimmed and collapsed and a short one is refused`() {
        assertEquals("game of thrones", keywordTerm("  game   of\tthrones "))
        assertEquals(100, keywordTerm("x".repeat(150))?.length)
        assertNull(keywordTerm(" a "))
        assertNull(keywordTerm("   "))
    }

    @Test
    fun `a series with the default padding leaves the paddings to the server`() = runTest {
        val http = FakeHttp(this) { json(createdRule, 201) }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertTrue(schedule.toggleSeries(news, SeriesOptions()))
        assertEquals(
            """POST /api/v1/recording-rules {"title":"Evening News","channelId":"c1"}""",
            http.requestsOfKind("POST").single(),
        )
    }

    @Test
    fun `a series with a padding and any channel sends both`() = runTest {
        val http = FakeHttp(this) { json(createdRule, 201) }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertTrue(schedule.toggleSeries(news, SeriesOptions(RecordingPadding.OVERRUN, anyChannel = true)))
        assertEquals(
            "POST /api/v1/recording-rules " +
                """{"title":"Evening News","startPaddingSeconds":60,"endPaddingSeconds":1800}""",
            http.requestsOfKind("POST").single(),
        )
    }

    @Test
    fun `a rule changes its padding and drops its channel in one patch`() = runTest {
        val http = FakeHttp(this) { seen ->
            when {
                seen.method == "PATCH" -> json("""{"id":"s1","title":"Evening News"}""")

                seen.path == "/api/v1/recording-rules" -> json(
                    """{"rules":[{"id":"s1","title":"Evening News","channelId":"c1"}]}""",
                )

                else -> json("{}")
            }
        }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()
        val rule = (schedule.state.value as RecordingsState.Ready).rules.single()

        assertTrue(schedule.updateRule(rule, true, 0, RecordingPadding.NONE, anyChannel = true))
        assertEquals(
            "PATCH /api/v1/recording-rules/s1 " +
                """{"anyChannel":true,"newOnly":true,"keep":0,"startPaddingSeconds":0,"endPaddingSeconds":0}""",
            http.requestsOfKind("PATCH").single(),
        )
    }

    @Test
    fun `a rule reads its padding from the server and names the choice it matches`() = runTest {
        val http = FakeHttp(this) { seen ->
            if (seen.path == "/api/v1/recording-rules") {
                json(
                    """{"rules":[
                    {"id":"a","title":"A","startPaddingSeconds":0,"endPaddingSeconds":0},
                    {"id":"b","title":"B","startPaddingSeconds":10,"endPaddingSeconds":20}
                ]}""",
                )
            } else {
                json("{}")
            }
        }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        val rules = (schedule.state.value as RecordingsState.Ready).rules
        assertEquals(listOf(RecordingPadding.NONE, null), rules.map { it.padding })
    }

    @Test
    fun `a window records the channel from now to the end of the window`() = runTest {
        val http = FakeHttp(this) { json(createdRecording, 201) }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()
        val now = Instant.parse("2026-10-18T18:00:00Z")

        assertTrue(schedule.recordWindow("c9", RecordingWindow.ONE_HOUR, now))
        assertTrue(schedule.recordWindow("c9", RecordingWindow.HALF_HOUR, now))
        assertTrue(schedule.recordWindow("c9", RecordingWindow.TWO_HOURS, now))
        assertEquals(
            listOf(
                "POST /api/v1/recordings " +
                    """{"channelId":"c9","startsAt":"2026-10-18T18:00:00Z","endsAt":"2026-10-18T19:00:00Z"}""",
                "POST /api/v1/recordings " +
                    """{"channelId":"c9","startsAt":"2026-10-18T18:00:00Z","endsAt":"2026-10-18T18:30:00Z"}""",
                "POST /api/v1/recordings " +
                    """{"channelId":"c9","startsAt":"2026-10-18T18:00:00Z","endsAt":"2026-10-18T20:00:00Z"}""",
            ),
            http.requestsOfKind("POST"),
        )
    }

    @Test
    fun `a window refused by the server says so`() = runTest {
        val http = FakeHttp(this) { seen ->
            if (seen.method == "POST") apiError(404, "CHANNEL_NOT_FOUND") else json("{}")
        }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertFalse(schedule.recordWindow("gone", RecordingWindow.ONE_HOUR, Instant.parse("2026-10-18T18:00:00Z")))
    }

    @Test
    fun `a window on a server that does not record is never sent`() = runTest {
        val http = FakeHttp(this) { json("{}") }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(false))
        runCurrent()

        assertFalse(schedule.recordWindow("c1", RecordingWindow.ONE_HOUR, Instant.parse("2026-10-18T18:00:00Z")))
        assertEquals(emptyList(), http.requestsOfKind("POST"))
    }

    @Test
    fun `a keyword rule is added for the phrase on any channel and a second press deletes it`() = runTest {
        var added = false
        val http = FakeHttp(this) { seen ->
            when {
                seen.method == "POST" -> {
                    added = true
                    json(
                        """{"id":"k1","title":"world cup","match":{"kind":"keyword","terms":["world cup"]}}""",
                        201,
                    )
                }

                seen.method == "DELETE" -> {
                    added = false
                    json("{}", 204)
                }

                seen.path == "/api/v1/recording-rules" -> json(
                    if (added) {
                        """{"rules":[{"id":"k1","title":"world cup",""" +
                            """"match":{"kind":"keyword","terms":["world cup"]}}]}"""
                    } else {
                        "{}"
                    },
                )

                else -> json("{}")
            }
        }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertTrue(schedule.toggleKeyword("  World   Cup "))
        assertEquals(
            """POST /api/v1/recording-rules {"title":"World Cup","match":{"kind":"keyword","terms":["World Cup"]}}""",
            http.requestsOfKind("POST").single(),
        )
        assertTrue(schedule.marks.value.recordsKeyword("world cup"))
        assertFalse(schedule.marks.value.of(news).series, "a keyword rule never marks a title as a series")

        assertTrue(schedule.toggleKeyword("world cup"))
        assertEquals(listOf("DELETE /api/v1/recording-rules/k1"), http.requestsOfKind("DELETE"))
        assertFalse(schedule.marks.value.recordsKeyword("world cup"))
    }

    @Test
    fun `a keyword too short to match anything is not sent`() = runTest {
        val http = FakeHttp(this) { json("{}") }
        val schedule = RecordingSchedule(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertFalse(schedule.toggleKeyword("a"))
        assertEquals(emptyList(), http.requestsOfKind("POST"))
    }
}
