package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.Wire
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ICS_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/** RFC 5545's longest content line, in octets, before it folds onto the next. */
private const val LINE_OCTETS = 75

/**
 * The scheduled and running [recordings] as an iCalendar feed, one event each over the window the DVR records,
 * paddings included. A calendar app polls it, so a recording a series rule adds shows there too.
 */
internal fun recordingsCalendar(recordings: List<Recording>, now: Instant): String {
    val lines = mutableListOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Sofa King//Recordings//EN",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        "X-WR-CALNAME:Sofa King recordings",
        "REFRESH-INTERVAL;VALUE=DURATION:PT15M",
        "X-PUBLISHED-TTL:PT15M",
    )
    val upcoming = recordings.filter {
        it.state == Wire.RecordingState.SCHEDULED || it.state == Wire.RecordingState.RECORDING
    }
    for (recording in upcoming) {
        val start = Instant.parse(recording.startsAt).minusSeconds(recording.startPaddingSeconds.toLong())
        val end = Instant.parse(recording.endsAt).plusSeconds(recording.endPaddingSeconds.toLong())
        val summary = listOfNotNull(recording.title, recording.episodeTitle).joinToString(": ")
        lines += listOfNotNull(
            "BEGIN:VEVENT",
            "UID:${recording.id}@sofa-king",
            "DTSTAMP:${ICS_TIME.format(now)}",
            "DTSTART:${ICS_TIME.format(start)}",
            "DTEND:${ICS_TIME.format(end)}",
            "SUMMARY:${text(summary)}",
            recording.channelName.takeIf { it.isNotEmpty() }?.let { "LOCATION:${text(it)}" },
            recording.description?.let { "DESCRIPTION:${text(it)}" },
            "STATUS:CONFIRMED",
            "CATEGORIES:" + if (recording.state == Wire.RecordingState.RECORDING) "Recording" else "Scheduled",
            "X-SOFA-KING-CONFLICT:TRUE".takeIf { recording.conflict },
            "END:VEVENT",
        )
    }
    lines += "END:VCALENDAR"
    return lines.joinToString("") { fold(it) + "\r\n" }
}

/** [value] as an iCalendar TEXT value: backslashes, semicolons, commas and line breaks escaped. */
private fun text(value: String): String = buildString {
    for (char in value) {
        when (char) {
            '\\', ';', ',' -> append('\\').append(char)
            '\n' -> append("\\n")
            '\r' -> Unit
            else -> append(char)
        }
    }
}

/** [line] folded so no physical line passes [LINE_OCTETS] octets of UTF-8, never inside a character. */
private fun fold(line: String): String {
    val out = StringBuilder()
    var octets = 0
    var index = 0
    while (index < line.length) {
        val codePoint = line.codePointAt(index)
        val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        // A continuation line starts with the space that folds it, which counts towards its length.
        if (octets + size > LINE_OCTETS) {
            out.append("\r\n ")
            octets = 1
        }
        out.appendCodePoint(codePoint)
        octets += size
        index += Character.charCount(codePoint)
    }
    return out.toString()
}
