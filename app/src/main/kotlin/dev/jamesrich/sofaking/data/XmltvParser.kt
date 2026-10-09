package dev.jamesrich.sofaking.data

import org.xmlpull.v1.XmlPullParser
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate

object XmltvParser {
    /** Reads `<programme>` elements; [parser] must already have its input set. */
    fun parse(parser: XmlPullParser, keepChannels: Set<String>? = null): Map<String, List<Programme>> {
        val byChannel = HashMap<String, MutableList<Programme>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                readProgramme(parser, keepChannels)?.let { p ->
                    byChannel.getOrPut(p.channelId) { mutableListOf() }.add(p)
                }
            }
            event = parser.next()
        }
        return byChannel.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    /** Returns null, leaving the parser after the element, when the programme is unwanted or incomplete. */
    private fun readProgramme(parser: XmlPullParser, keepChannels: Set<String>?): Programme? {
        val depth = parser.depth
        val channel = parser.getAttributeValue(null, "channel")
        if (channel == null || (keepChannels != null && channel !in keepChannels)) {
            skipElement(parser, depth)
            return null
        }
        val start = parser.getAttributeValue(null, "start")?.let(::parseTime)
        val stop = parser.getAttributeValue(null, "stop")?.let(::parseTime)
        if (start == null || stop == null) {
            skipElement(parser, depth)
            return null
        }
        var title: String? = null
        var subtitle: String? = null
        var description: String? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = title ?: parser.nextText()
                    "sub-title" -> subtitle = subtitle ?: parser.nextText()
                    "desc" -> description = description ?: parser.nextText()
                }
            }
            event = parser.next()
        }
        return Programme(channel, start, stop, title?.trim().orEmpty(), subtitle?.trim(), description?.trim())
    }

    /** Advances past the element whose start tag is at [depth], without reading its text. */
    private fun skipElement(parser: XmlPullParser, depth: Int) {
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.END_DOCUMENT) return
            event = parser.next()
        }
    }

    /**
     * Parses XMLTV `yyyyMMddHHmmss ±HHMM` into an [Instant]. The offset is required, as before;
     * surrounding whitespace is tolerated. Returns null for anything else.
     */
    internal fun parseTime(value: String): Instant? {
        val s = value.trim()
        if (s.length != TIME_LENGTH || s[14] != ' ') return null
        val offsetSign = when (s[15]) {
            '+' -> 1L
            '-' -> -1L
            else -> return null
        }
        val year = digits(s, 0, 4)
        val month = digits(s, 4, 2)
        val day = digits(s, 6, 2)
        val hour = digits(s, 8, 2)
        val minute = digits(s, 10, 2)
        val second = digits(s, 12, 2)
        val offsetHours = digits(s, 16, 2)
        val offsetMinutes = digits(s, 18, 2)
        if (year < 0 || month !in 1..12 || day < 1 || hour !in 0..23 || minute !in 0..59 ||
            second !in 0..59 || offsetHours !in 0..23 || offsetMinutes !in 0..59
        ) {
            return null
        }
        val epochDay = try {
            LocalDate.of(year, month, day).toEpochDay()
        } catch (_: DateTimeException) {
            return null
        }
        val localSeconds = epochDay * SECONDS_PER_DAY + hour * 3600L + minute * 60L + second
        val offsetSeconds = offsetSign * (offsetHours * 3600L + offsetMinutes * 60L)
        return Instant.ofEpochSecond(localSeconds - offsetSeconds)
    }

    /** Decimal value of [count] ASCII digits starting at [from], or -1 if any character is not a digit. */
    private fun digits(s: String, from: Int, count: Int): Int {
        var n = 0
        for (i in from until from + count) {
            val d = s[i] - '0'
            if (d !in 0..9) return -1
            n = n * 10 + d
        }
        return n
    }

    private const val TIME_LENGTH = 20
    private const val SECONDS_PER_DAY = 86_400L
}
