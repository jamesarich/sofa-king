package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.contract.Channel
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * [skipped] counts malformed entries by short reason; the import still succeeds with the well-formed rest.
 * [notes] counts imported entries whose identity was derived: `url-identity` and `merged-key`.
 */
data class ParsedPlaylist(
    val channels: List<Channel>,
    val offerings: List<Offering>,
    val skipped: Map<String, Int> = emptyMap(),
    val notes: Map<String, Int> = emptyMap(),
) {
    val skippedCount: Int get() = skipped.values.sum()

    /** For example `missing-id=3, duplicate-source=1`, or null when nothing was skipped. */
    val skipSummary: String? get() = summary(skipped)

    /** For example `merged-key=2, url-identity=724`, or null when every identity was published. */
    val noteSummary: String? get() = summary(notes)

    private fun summary(counts: Map<String, Int>) = counts.entries.sortedBy {
        it.key
    }.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { null }
}

/** [controlCharacters] counts bytes XML 1.0 forbids (C0 controls other than tab, LF and CR) that were dropped before parsing. */
data class ParsedGuide(
    val channels: List<GuideChannel>,
    val programmes: List<GuideProgramme>,
    val controlCharacters: Int = 0,
)

/**
 * Drops the C0 control bytes XML 1.0 forbids, which real guides carry in titles (a raw backspace, for
 * one) and which would otherwise fail the whole import. Those bytes are never part of a multi-byte
 * sequence in UTF-8 or a single-byte encoding; a UTF-16 or UTF-32 document, recognized by a BOM or a
 * NUL in its first bytes, passes through untouched.
 */
private class ControlCharacterStrippingInputStream(input: InputStream) : FilterInputStream(input) {
    var dropped = 0
        private set
    private var active = true

    /** The first four bytes, read ahead for the encoding decision and served before the rest. */
    private var head: ByteArray? = null
    private var headOffset = 0

    override fun read(): Int {
        val one = ByteArray(1)
        while (true) {
            val count = read(one, 0, 1)
            if (count < 0) return -1
            if (count == 1) return one[0].toInt() and 0xff
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val count = fill(b, off, len)
            if (count <= 0 || !active) return count
            var kept = 0
            for (index in off until off + count) {
                val byte = b[index].toInt() and 0xff
                if (forbidden(byte)) dropped++ else b[off + kept++] = b[index]
            }
            if (kept > 0) return kept
        }
    }

    private fun fill(b: ByteArray, off: Int, len: Int): Int {
        val pending = head ?: sniff()
        if (headOffset < pending.size) {
            val count = minOf(len, pending.size - headOffset)
            System.arraycopy(pending, headOffset, b, off, count)
            headOffset += count
            return count
        }
        return super.read(b, off, len)
    }

    /** The parser reads the prolog a byte at a time, so the decision needs its own read-ahead. */
    private fun sniff(): ByteArray {
        val bytes = ByteArray(4)
        var read = 0
        while (read < 4) {
            val count = super.read(bytes, read, 4 - read)
            if (count < 0) break
            read += count
        }
        val first = bytes.copyOf(read)
        val bom16 =
            read >= 2 && (
                (first[0] == 0xfe.toByte() && first[1] == 0xff.toByte()) ||
                    (first[0] == 0xff.toByte() && first[1] == 0xfe.toByte())
                )
        active = !bom16 && first.none { it == 0.toByte() }
        head = first
        return first
    }

    private fun forbidden(byte: Int) = byte < 0x20 && byte != 0x09 && byte != 0x0a && byte != 0x0d
}

/** Fails once more than [limit] decompressed bytes have been read, so a hostile guide cannot run unbounded. */
private class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) consumed(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
        if (it > 0) {
            consumed(it.toLong())
        }
    }

    private fun consumed(bytes: Long) {
        count += bytes
        require(count <= limit) { "Guide exceeds size limit" }
    }
}

object FeedParser {
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun playlist(
        provider: String,
        text: String,
        defaultProtocol: String? = null,
        renewable: Set<String> = DEFAULT_RENEWABLE_QUERY,
        /** The playlist's own URL; relative `tvg-logo` references resolve against it. */
        baseUrl: String? = null,
    ): ParsedPlaylist {
        require(text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) { "Not an extended M3U playlist" }
        val channels = linkedMapOf<String, Channel>()
        val offerings = mutableListOf<Offering>()
        val offeringIds = HashSet<String>()
        val skipped = sortedMapOf<String, Int>()
        val notes = sortedMapOf<String, Int>()
        fun skip(reason: String) {
            skipped.merge(reason, 1, Int::plus)
        }
        fun note(reason: String) {
            notes.merge(reason, 1, Int::plus)
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
            val name = if (split < 0) {
                ""
            } else {
                metadata.substring(split + 1).trim().ifBlank { attrs["tvg-name"].orEmpty() }
            }
            if (name.isBlank()) {
                skip("missing-name")
                continue
            }
            val protocol =
                attrs["stream-type"] ?: defaultProtocol ?: if (uri.path.endsWith(".m3u8", true)) "hls" else "mpegts"
            if (protocol !in setOf("hls", "mpegts")) {
                skip("bad-protocol")
                continue
            }
            val sourceKey = attrs["stream-id"] ?: checkNotNull(sourceKey(line, renewable))
            val published = attrs["channel-id"] ?: attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: attrs["channel-uuid"]
            // An entry without a published identity is its own channel, keyed by its URL; the name is display only.
            val key = published ?: "url\u0000$sourceKey"
            val id =
                try {
                    attrs["channel-uuid"]?.let { java.util.UUID.fromString(it).toString() }
                        ?: stableId("channel:$provider:$key")
                } catch (_: IllegalArgumentException) {
                    skip("invalid-uuid")
                    continue
                }
            val channel =
                Channel(
                    id,
                    attrs["tvg-chno"] ?: attrs["channel-number"] ?: published ?: id.take(8),
                    name,
                    normalizeLogoUrl(attrs["tvg-logo"], baseUrl),
                    attrs["group-title"]?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
                    attrs["tvg-id"],
                )
            val offeringId = stableId("offering:$provider:$key:$sourceKey")
            if (!offeringIds.add(offeringId)) {
                skip("duplicate-source")
                continue
            }
            if (published == null) note("url-identity")
            // Multiple URLs with one exact provider key are alternatives of the first entry, never fuzzy name merges.
            if (channels[id]?.let { it.name == name && it.displayNumber == channel.displayNumber } == false) {
                note("merged-key")
            }
            channels.putIfAbsent(id, channel)
            offerings += Offering(offeringId, id, provider, line, protocol)
            require(channels.size <= 10_000 && offerings.size <= 20_000) { "Playlist exceeds import limits" }
        }
        return ParsedPlaylist(channels.values.toList(), offerings, skipped, notes)
    }

    /**
     * Parses XMLTV from [input] (plain or gzip), keeping only programmes whose channel [wanted] accepts.
     *
     * A `<!DOCTYPE tv SYSTEM "xmltv.dtd">` declaration is reported and skipped, never processed: the
     * external DTD is not fetched, internal-subset declarations are ignored, and a reference to an entity
     * declared there fails the parse as undeclared instead of resolving.
     */
    fun guide(
        input: InputStream,
        /** The guide's own URL; relative `<icon src>` references resolve against it. */
        baseUrl: String? = null,
        wanted: (String) -> Boolean,
    ): ParsedGuide {
        val factory =
            XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty("javax.xml.stream.isSupportingExternalEntities", false)
                setXMLResolver { _, _, _, _ -> throw IllegalArgumentException("External XML entities are forbidden") }
            }
        val stripped = ControlCharacterStrippingInputStream(BoundedInputStream(decompressed(input), GUIDE_BYTE_LIMIT))
        val reader = factory.createXMLStreamReader(stripped)
        val channels = linkedMapOf<String, GuideChannel>()
        val seen = HashSet<String>()
        val programmes = ArrayList<GuideProgramme>()
        val keys = HashSet<String>()
        try {
            var root = false
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        if (!root) {
                            require(reader.localName == "tv") { "Not XMLTV" }
                            root = true
                            continue
                        }
                        when (reader.localName) {
                            "channel" -> {
                                val id = reader.getAttributeValue(null, "id")?.trim()
                                var name: String? = null
                                var icon: String? = null
                                while (reader.hasNext()) {
                                    val event = reader.next()
                                    if (event == XMLStreamConstants.END_ELEMENT && reader.localName == "channel") break
                                    if (event != XMLStreamConstants.START_ELEMENT) continue
                                    when (reader.localName) {
                                        "display-name" -> {
                                            val text = reader.elementText.trim().take(256)
                                            if (name == null && text.isNotEmpty()) name = text
                                        }

                                        "icon" -> if (icon == null) {
                                            icon =
                                                normalizeLogoUrl(reader.getAttributeValue(null, "src"), baseUrl)
                                        }
                                    }
                                }
                                if (!id.isNullOrEmpty()) channels.putIfAbsent(id, GuideChannel(id, name ?: id, icon))
                                require(channels.size <= GUIDE_CHANNEL_LIMIT) { "Guide exceeds import limits" }
                            }

                            "programme" -> {
                                val channel = reader.getAttributeValue(null, "channel")?.trim().orEmpty()
                                val start = reader.getAttributeValue(null, "start")
                                val stop = reader.getAttributeValue(null, "stop")
                                val keep = channel.isNotEmpty() && wanted(channel)
                                val read = readProgramme(reader, baseUrl, keep)
                                val title = read.title
                                if (channel.isNotEmpty() && seen.add(channel)) {
                                    require(seen.size <= GUIDE_CHANNEL_LIMIT) { "Guide exceeds import limits" }
                                }
                                if (!keep) continue
                                val from = xmlTime(start)
                                val to = xmlTime(stop)
                                if (from == null || to == null || !to.isAfter(from) || title.isBlank()) continue
                                if (!keys.add("$channel\u0000$from\u0000$to\u0000$title")) continue
                                programmes +=
                                    read.copy(
                                        channelId = channel,
                                        startsAt = from,
                                        endsAt = to,
                                        title = title.trim(),
                                    ).bounded()
                                require(programmes.size <= GUIDE_PROGRAMME_LIMIT) { "Guide exceeds import limits" }
                            }
                        }
                    }
                }
            }
            require(root) { "Empty XMLTV" }
        } finally {
            reader.close()
        }
        for (id in seen) channels.putIfAbsent(id, GuideChannel(id, id))
        return ParsedGuide(
            channels.values.toList(),
            programmes.sortedWith(
                compareBy({
                    it.channelId
                }, { it.startsAt }),
            ),
            stripped.dropped,
        )
    }

    fun guide(text: String, wanted: (String) -> Boolean = { true }): ParsedGuide =
        guide(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), wanted = wanted)

    /**
     * Reads a `<programme>`'s children up to its end tag. Only direct children count, so an `<icon>` or `<value>`
     * inside credits or a star rating is never taken for the programme's own. Without [collect] the element is only
     * consumed. The result carries no channel or times; the caller sets them.
     */
    private fun readProgramme(reader: XMLStreamReader, baseUrl: String?, collect: Boolean): GuideProgramme {
        var title = ""
        var description: String? = null
        var subTitle: String? = null
        val numbers = ArrayList<EpisodeNumber>()
        var icon: String? = null
        val categories = ArrayList<String>()
        var rating: ProgrammeRating? = null
        var isNew = false
        var previouslyShown: PreviouslyShown? = null
        var date: String? = null
        var titleLanguage: String? = null
        var descLanguage: String? = null
        var languageElement: String? = null
        var depth = 0
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == 0) break
                depth--
                continue
            }
            if (event != XMLStreamConstants.START_ELEMENT) continue
            if (depth > 0 || !collect) {
                depth++
                continue
            }
            // Each branch either reads the element through its end tag or counts it as open.
            when (reader.localName) {
                "title" -> {
                    val lang = reader.getAttributeValue(null, "lang")
                    val text = reader.elementText.take(GuideProgramme.TITLE_LIMIT)
                    if (title.isBlank()) {
                        title = text
                        titleLanguage = lang
                    }
                }

                "sub-title" -> {
                    val text = reader.elementText.take(GuideProgramme.TITLE_LIMIT).trim()
                    if (subTitle == null && text.isNotEmpty()) subTitle = text
                }

                "desc" -> {
                    val lang = reader.getAttributeValue(null, "lang")
                    val text = reader.elementText.take(GuideProgramme.DESCRIPTION_LIMIT)
                    if (description == null) {
                        description = text
                        descLanguage = lang
                    }
                }

                "episode-num" -> {
                    val system = reader.getAttributeValue(null, "system")?.trim()?.ifEmpty { null } ?: "onscreen"
                    val text = reader.elementText.trim()
                    if (numbers.size < GuideProgramme.EPISODE_NUMBER_LIMIT && text.isNotEmpty() &&
                        text.length <= GuideProgramme.EPISODE_VALUE_LIMIT
                    ) {
                        numbers += EpisodeNumber(system.take(64), text)
                    }
                }

                "category" -> {
                    val text = reader.elementText.trim().take(GuideProgramme.CATEGORY_LENGTH_LIMIT)
                    if (categories.size < GuideProgramme.CATEGORY_LIMIT && text.isNotEmpty()) categories += text
                }

                "date" -> {
                    val text = reader.elementText.take(64)
                    if (date == null) date = text
                }

                "language" -> {
                    val text = reader.elementText.trim().take(16)
                    if (languageElement == null && text.isNotEmpty()) languageElement = text
                }

                "rating" -> {
                    val system = reader.getAttributeValue(null, "system")?.take(64)
                    val value = ratingValue(reader)
                    if (rating == null && value != null) rating = ProgrammeRating(value, system)
                }

                "icon" -> {
                    if (icon == null) icon = normalizeLogoUrl(reader.getAttributeValue(null, "src"), baseUrl)
                    depth++
                }

                "new" -> {
                    isNew = true
                    depth++
                }

                "previously-shown" -> {
                    if (previouslyShown == null) {
                        previouslyShown = PreviouslyShown(reader.getAttributeValue(null, "start")?.take(64))
                    }
                    depth++
                }

                else -> depth++
            }
        }
        return GuideProgramme(
            "", Instant.EPOCH, Instant.EPOCH, title, description, subTitle, numbers, icon, categories, rating, isNew,
            previouslyShown, date,
            titleLanguage ?: descLanguage ?: languageElement,
        )
    }

    /** The text of a `<rating>`'s direct `<value>` child, reading the rating through its end tag. */
    private fun ratingValue(reader: XMLStreamReader): String? {
        var value: String? = null
        var depth = 0
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == 0) break
                depth--
                continue
            }
            if (event != XMLStreamConstants.START_ELEMENT) continue
            if (depth == 0 && reader.localName == "value" && value == null) {
                value = reader.elementText.trim().take(64).ifEmpty { null }
            } else {
                depth++
            }
        }
        return value
    }

    /** Gzip is recognized by its magic bytes, whatever the URL or content type says. */
    private fun decompressed(input: InputStream): InputStream {
        val stream = PushbackInputStream(input, 2)
        val head = ByteArray(2)
        var read = 0
        while (read < 2) {
            val count = stream.read(head, read, 2 - read)
            if (count < 0) break
            read += count
        }
        if (read > 0) stream.unread(head, 0, read)
        return if (read == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) {
            GZIPInputStream(stream, 65_536)
        } else {
            stream
        }
    }

    private const val GUIDE_BYTE_LIMIT = 1L shl 30
    private const val GUIDE_CHANNEL_LIMIT = 100_000
    private const val GUIDE_PROGRAMME_LIMIT = 1_000_000

    private fun xmlTime(value: String?): Instant? = try {
        // Reject unspecified local zones; never silently shift a regional schedule.
        OffsetDateTime
            .parse(
                value?.trim(),
                DateTimeFormatter.ofPattern(
                    "uuuuMMddHHmmss Z",
                ).withResolverStyle(java.time.format.ResolverStyle.STRICT),
            ).toInstant()
    } catch (_: Exception) {
        null
    }
}
