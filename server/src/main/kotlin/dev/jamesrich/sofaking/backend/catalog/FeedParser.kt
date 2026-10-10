package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.Channel
import dev.jamesrich.sofaking.backend.api.Programme
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

object FeedParser {
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun playlist(
        provider: String,
        text: String,
        defaultProtocol: String? = null,
    ): Pair<List<Channel>, List<Offering>> {
        require(text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) { "Not an extended M3U playlist" }
        val channels = linkedMapOf<String, Channel>()
        val offerings = mutableListOf<Offering>()
        var info: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF:")) {
                info = line
                continue
            }
            if (line.isBlank() || line.startsWith('#')) continue
            val metadata = requireNotNull(info) { "Stream has no EXTINF metadata" }
            info = null
            httpUri(line)
            val attrs = attribute.findAll(metadata).associate { it.groupValues[1] to it.groupValues[2] }
            // Use the comma outside quoted attributes, preserving commas in station names.
            var quoted = false
            val split =
                metadata.indexOfFirst { c ->
                    if (c == '"') quoted = !quoted
                    c == ',' && !quoted
                }
            require(split >= 0) { "Missing station name" }
            val name = metadata.substring(split + 1).trim().ifBlank { attrs["tvg-name"].orEmpty() }
            require(name.isNotBlank()) { "Empty station name" }
            val key =
                attrs["channel-id"] ?: attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    ?: attrs["channel-uuid"] ?: throw IllegalArgumentException("A stable tvg-id, channel-id or channel-uuid is required")
            val id =
                attrs["channel-uuid"]?.let {
                    java.util.UUID
                        .fromString(it)
                        .toString()
                } ?: stableId("channel:$provider:$key")
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
            require(
                channels[id]?.let { it.name == name && it.displayNumber == channel.displayNumber } != false,
            ) { "Ambiguous duplicate station key" }
            channels.putIfAbsent(id, channel)
            val protocol = attrs["stream-type"] ?: defaultProtocol ?: if (httpUri(line).path.endsWith(".m3u8", true)) "hls" else "mpegts"
            require(protocol in setOf("hls", "mpegts")) { "Unsupported stream protocol" }
            val uri = httpUri(line)
            val sourceKey = attrs["stream-id"] ?: "${uri.scheme}://${uri.authority}${uri.path}"
            val offeringId = stableId("offering:$provider:$key:$sourceKey")
            require(offerings.none { it.id == offeringId }) { "Ambiguous source identity: supply distinct stream-id attributes" }
            offerings += Offering(offeringId, id, provider, line, protocol)
            require(channels.size <= 10_000 && offerings.size <= 20_000) { "Playlist exceeds import limits" }
        }
        return channels.values.toList() to offerings
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
