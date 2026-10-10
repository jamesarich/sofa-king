package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.Channel
import dev.jamesrich.sofaking.backend.api.Programme
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** [skipped] counts malformed entries by short reason; the import still succeeds with the well-formed rest. */
data class ParsedPlaylist(
    val channels: List<Channel>,
    val offerings: List<Offering>,
    val skipped: Map<String, Int> = emptyMap(),
) {
    val skippedCount: Int get() = skipped.values.sum()

    /** For example `missing-id=3, duplicate-source=1`, or null when nothing was skipped. */
    val skipSummary: String? get() = skipped.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { null }
}

object FeedParser {
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun playlist(
        provider: String,
        text: String,
        defaultProtocol: String? = null,
    ): ParsedPlaylist {
        require(text.trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U")) { "Not an extended M3U playlist" }
        val channels = linkedMapOf<String, Channel>()
        val offerings = mutableListOf<Offering>()
        val offeringIds = HashSet<String>()
        val skipped = sortedMapOf<String, Int>()
        fun skip(reason: String) {
            skipped.merge(reason, 1, Int::plus)
        }
        var info: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF:")) {
                info = line
                continue
            }
            if (line.isBlank() || line.startsWith('#')) continue
            val metadata = info
            info = null
            if (metadata == null) {
                skip("no-extinf")
                continue
            }
            val uri =
                try {
                    httpUri(line)
                } catch (_: Exception) {
                    skip("invalid-url")
                    continue
                }
            val attrs = attribute.findAll(metadata).associate { it.groupValues[1] to it.groupValues[2] }
            // Use the comma outside quoted attributes, preserving commas in station names.
            var quoted = false
            val split =
                metadata.indexOfFirst { c ->
                    if (c == '"') quoted = !quoted
                    c == ',' && !quoted
                }
            val name = if (split < 0) "" else metadata.substring(split + 1).trim().ifBlank { attrs["tvg-name"].orEmpty() }
            if (name.isBlank()) {
                skip("missing-name")
                continue
            }
            val key = attrs["channel-id"] ?: attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: attrs["channel-uuid"]
            if (key == null) {
                skip("missing-id")
                continue
            }
            val id =
                try {
                    attrs["channel-uuid"]?.let { java.util.UUID.fromString(it).toString() } ?: stableId("channel:$provider:$key")
                } catch (_: IllegalArgumentException) {
                    skip("invalid-uuid")
                    continue
                }
            val channel =
                Channel(
                    id,
                    attrs["tvg-chno"] ?: attrs["channel-number"] ?: key,
                    name,
                    attrs["tvg-logo"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                    attrs["group-title"]?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
                    attrs["tvg-id"],
                )
            // Multiple URLs with one exact provider key are alternatives, never fuzzy name merges.
            if (channels[id]?.let { it.name == name && it.displayNumber == channel.displayNumber } == false) {
                skip("duplicate-key")
                continue
            }
            val protocol = attrs["stream-type"] ?: defaultProtocol ?: if (uri.path.endsWith(".m3u8", true)) "hls" else "mpegts"
            if (protocol !in setOf("hls", "mpegts")) {
                skip("bad-protocol")
                continue
            }
            val sourceKey = attrs["stream-id"] ?: "${uri.scheme}://${uri.authority}${uri.path}"
            val offeringId = stableId("offering:$provider:$key:$sourceKey")
            if (!offeringIds.add(offeringId)) {
                skip("duplicate-source")
                continue
            }
            channels.putIfAbsent(id, channel)
            offerings += Offering(offeringId, id, provider, line, protocol)
            require(channels.size <= 10_000 && offerings.size <= 20_000) { "Playlist exceeds import limits" }
        }
        return ParsedPlaylist(channels.values.toList(), offerings, skipped)
    }

    fun guide(
        text: String,
        channels: List<Channel>,
    ): List<Programme> {
        val joins = channels.filter { it.legacyGuideId != null }.groupBy { it.legacyGuideId }
        val factory =
            XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty("javax.xml.stream.isSupportingExternalEntities", false)
                setXMLResolver { _, _, _, _ -> throw IllegalArgumentException("External XML entities are forbidden") }
            }
        val reader = factory.createXMLStreamReader(StringReader(text))
        val result = linkedMapOf<String, Programme>()
        try {
            var root = false
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD -> {
                        error("XML DTD is forbidden")
                    }

                    XMLStreamConstants.START_ELEMENT -> {
                        if (!root) {
                            require(reader.localName == "tv") { "Not XMLTV" }
                            root = true
                        }
                        if (reader.localName != "programme") continue
                        val matched = joins[reader.getAttributeValue(null, "channel")].orEmpty()
                        val start = reader.getAttributeValue(null, "start")
                        val stop = reader.getAttributeValue(null, "stop")
                        var title = ""
                        var description: String? = null
                        while (reader.hasNext()) {
                            val event = reader.next()
                            if (event == XMLStreamConstants.DTD) error("XML DTD is forbidden")
                            if (event == XMLStreamConstants.END_ELEMENT && reader.localName == "programme") break
                            if (event == XMLStreamConstants.START_ELEMENT &&
                                reader.localName == "title"
                            ) {
                                title = reader.elementText.take(4096)
                            } else if (event == XMLStreamConstants.START_ELEMENT &&
                                reader.localName == "desc"
                            ) {
                                description = reader.elementText.take(16384)
                            }
                        }
                        if (matched.isEmpty()) continue
                        val from = xmlTime(start)
                        val to = xmlTime(stop)
                        if (from == null || to == null || !to.isAfter(from) || title.isBlank()) continue
                        for (channel in matched) {
                            val id = stableId("programme:${channel.id}:$from:$to:$title")
                            result[id] = Programme(id, channel.id, from.toString(), to.toString(), title, description)
                        }
                        require(result.size <= 250_000) { "Guide exceeds import limits" }
                    }
                }
            }
            require(root) { "Empty XMLTV" }
        } finally {
            reader.close()
        }
        return result.values.sortedWith(compareBy({ it.channelId }, { it.startsAt }))
    }

    private fun xmlTime(value: String?): Instant? =
        try {
            // Reject unspecified local zones; never silently shift a regional schedule.
            OffsetDateTime
                .parse(
                    value?.trim(),
                    DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withResolverStyle(java.time.format.ResolverStyle.STRICT),
                ).toInstant()
        } catch (_: Exception) {
            null
        }
}
