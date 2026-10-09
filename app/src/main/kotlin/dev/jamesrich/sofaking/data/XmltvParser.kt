package dev.jamesrich.sofaking.data

import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object XmltvParser {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")

    /** Reads `<programme>` elements; [parser] must already have its input set. */
    fun parse(parser: XmlPullParser, keepChannels: Set<String>? = null): Map<String, List<Programme>> {
        val byChannel = HashMap<String, MutableList<Programme>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                readProgramme(parser)?.let { p ->
                    if (keepChannels == null || p.channelId in keepChannels) {
                        byChannel.getOrPut(p.channelId) { mutableListOf() }.add(p)
                    }
                }
            }
            event = parser.next()
        }
        return byChannel.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    private fun readProgramme(parser: XmlPullParser): Programme? {
        val channel = parser.getAttributeValue(null, "channel") ?: return null
        val start = parser.getAttributeValue(null, "start")?.let(::parseTime) ?: return null
        val stop = parser.getAttributeValue(null, "stop")?.let(::parseTime) ?: return null
        var title: String? = null
        var subtitle: String? = null
        var description: String? = null
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = title ?: parser.nextText()
                    "sub-title" -> subtitle = subtitle ?: parser.nextText()
                    "desc" -> description = description ?: parser.nextText()
                }
            }
        }
        return Programme(channel, start, stop, title?.trim().orEmpty(), subtitle?.trim(), description?.trim())
    }

    internal fun parseTime(value: String): Instant? =
        runCatching { OffsetDateTime.parse(value.trim(), timeFormat).toInstant() }.getOrNull()
}
