package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.Wire
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingsCalendarTest {
    private val now = Instant.parse("2026-10-25T12:00:00Z")

    private fun recording(id: String, state: String, title: String = "News") = Recording(
        id = id,
        channelId = "c1",
        channelName = "Channel One",
        title = title,
        startsAt = "2026-10-25T18:00:00Z",
        endsAt = "2026-10-25T19:00:00Z",
        startPaddingSeconds = 60,
        endPaddingSeconds = 120,
        state = state,
    )

    /** The calendar's lines with each fold undone. */
    private fun unfolded(calendar: String) = calendar.replace("\r\n ", "").split("\r\n").dropLast(1)

    @Test fun `scheduled and running recordings are events over their padded window, and the rest are left out`() {
        val calendar = recordingsCalendar(
            listOf(
                recording("a", Wire.RecordingState.SCHEDULED).copy(episodeTitle = "Evening", conflict = true),
                recording("b", Wire.RecordingState.RECORDING),
                recording("c", Wire.RecordingState.COMPLETED),
                recording("d", Wire.RecordingState.FAILED),
            ),
            now,
        )
        val lines = unfolded(calendar)
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertEquals("END:VCALENDAR", lines.last())
        assertEquals(listOf("UID:a@sofa-king", "UID:b@sofa-king"), lines.filter { it.startsWith("UID:") })
        assertEquals(2, lines.count { it == "BEGIN:VEVENT" })
        assertEquals(2, lines.count { it == "END:VEVENT" })
        assertTrue("DTSTART:20261025T175900Z" in lines, calendar)
        assertTrue("DTEND:20261025T190200Z" in lines, calendar)
        assertTrue("DTSTAMP:20261025T120000Z" in lines, calendar)
        assertTrue("SUMMARY:News: Evening" in lines, calendar)
        assertTrue("LOCATION:Channel One" in lines, calendar)
        assertEquals(
            listOf("CATEGORIES:Scheduled", "CATEGORIES:Recording"),
            lines.filter { it.startsWith("CATEGORIES:") },
        )
        assertEquals(1, lines.count { it == "X-SOFA-KING-CONFLICT:TRUE" })
        assertFalse(calendar.replace("\r\n", "").contains('\n'), "every line ends in CRLF")
    }

    @Test fun `text is escaped and long lines fold within 75 octets without splitting a character`() {
        val title = "Café, Bar; and \\ more\nnews " + "é".repeat(60)
        val calendar = recordingsCalendar(
            listOf(recording("a", Wire.RecordingState.SCHEDULED, title).copy(description = "x".repeat(200))),
            now,
        )
        for (line in calendar.split("\r\n")) {
            assertTrue(line.toByteArray().size <= 75, "${line.toByteArray().size} octets: $line")
        }
        val lines = unfolded(calendar)
        assertTrue("SUMMARY:Café\\, Bar\\; and \\\\ more\\nnews " + "é".repeat(60) in lines, calendar)
        assertTrue("DESCRIPTION:" + "x".repeat(200) in lines, calendar)
    }

    @Test fun `an empty schedule is a calendar with no events`() {
        val lines = unfolded(recordingsCalendar(emptyList(), now))
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertEquals("END:VCALENDAR", lines.last())
        assertFalse(lines.any { it == "BEGIN:VEVENT" })
    }
}
