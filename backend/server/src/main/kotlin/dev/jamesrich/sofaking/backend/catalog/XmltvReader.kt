package dev.jamesrich.sofaking.backend.catalog

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/** How much of a short attribute or element, a time or a rating's value, is read; the program's limits follow. */
private const val SHORT_TEXT_LIMIT = 64
private const val CHANNEL_NAME_LIMIT = 256
private const val LANGUAGE_LIMIT = 16
private const val GUIDE_CHANNEL_LIMIT = 100_000
private const val GUIDE_PROGRAM_LIMIT = 1_000_000

/** XMLTV's times; an unspecified local zone is rejected, never silently shifting a regional schedule. */
private val XMLTV_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withResolverStyle(ResolverStyle.STRICT)

/**
 * Reads one XMLTV document from [reader] into its channels and the programs of the channels [wanted] accepts;
 * relative `<icon src>` references resolve against [baseUrl].
 */
internal class XmltvReader(
    private val reader: XMLStreamReader,
    private val baseUrl: String?,
    private val wanted: (String) -> Boolean,
) {
    private val channels = linkedMapOf<String, GuideChannel>()
    private val seen = HashSet<String>()
    private val programs = ArrayList<GuideProgram>()
    private val keys = HashSet<String>()

    /** The channels, every channel a program names among them, and the programs by channel and start. */
    fun read(): Pair<List<GuideChannel>, List<GuideProgram>> {
        var root = false
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
            if (root) {
                when (reader.localName) {
                    "channel" -> channel()
                    "programme" -> programme()
                }
            } else {
                require(reader.localName == "tv") { "Not XMLTV" }
                root = true
            }
        }
        require(root) { "Empty XMLTV" }
        for (id in seen) channels.putIfAbsent(id, GuideChannel(id, id))
        return channels.values.toList() to programs.sortedWith(compareBy({ it.channelId }, { it.startsAt }))
    }

    private fun channel() {
        val id = reader.getAttributeValue(null, "id")?.trim()
        var name: String? = null
        var icon: String? = null
        reader.forEachChild { child ->
            when (child) {
                "display-name" -> {
                    name = keepFirst(name, reader.elementText.trim().take(CHANNEL_NAME_LIMIT))
                    true
                }

                "icon" -> {
                    icon = icon ?: normalizeLogoUrl(reader.getAttributeValue(null, "src"), baseUrl)
                    false
                }

                else -> false
            }
        }
        if (!id.isNullOrEmpty()) channels.putIfAbsent(id, GuideChannel(id, name ?: id, icon))
        require(channels.size <= GUIDE_CHANNEL_LIMIT) { "Guide exceeds import limits" }
    }

    private fun programme() {
        val channel = reader.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = reader.getAttributeValue(null, "start")
        val stop = reader.getAttributeValue(null, "stop")
        val keep = channel.isNotEmpty() && wanted(channel)
        val program = ProgramReader(reader, baseUrl)
        reader.forEachChild { keep && program.child(it) }
        if (channel.isNotEmpty() && seen.add(channel)) {
            require(seen.size <= GUIDE_CHANNEL_LIMIT) { "Guide exceeds import limits" }
        }
        if (keep) keep(program.program(), channel, start, stop)
    }

    /** Keeps [read] on [channel] from [start] to [stop], unless its times or title are missing or it repeats one. */
    private fun keep(read: GuideProgram, channel: String, start: String?, stop: String?) {
        val from = xmlTime(start) ?: return
        val to = xmlTime(stop)?.takeIf { it.isAfter(from) } ?: return
        if (read.title.isBlank() || !keys.add("$channel\u0000$from\u0000$to\u0000${read.title}")) return
        programs += read.copy(channelId = channel, startsAt = from, endsAt = to, title = read.title.trim()).bounded()
        require(programs.size <= GUIDE_PROGRAM_LIMIT) { "Guide exceeds import limits" }
    }
}

/**
 * A `<programme>`'s children as [reader] reaches them. Of each kind the first wins, except that categories and
 * episode numbers collect up to their limits; a title or description keeps its `lang`. The program it makes carries no
 * channel or times; the caller sets them.
 */
private class ProgramReader(private val reader: XMLStreamReader, private val baseUrl: String?) {
    private var title = ""
    private var titleLanguage: String? = null
    private var description: String? = null
    private var descriptionLanguage: String? = null
    private var subTitle: String? = null
    private val numbers = ArrayList<EpisodeNumber>()
    private var icon: String? = null
    private val categories = ArrayList<String>()
    private var rating: ProgramRating? = null
    private var isNew = false
    private var previouslyShown: PreviouslyShown? = null
    private var date: String? = null
    private var language: String? = null

    /** Reads the child element [name], answering whether it read it through its end tag. */
    fun child(name: String): Boolean {
        when (name) {
            "title" -> title(attribute("lang"), reader.elementText.take(GuideProgram.TITLE_LIMIT))
            "sub-title" -> subTitle = keepFirst(subTitle, reader.elementText.take(GuideProgram.TITLE_LIMIT).trim())
            "desc" -> description(attribute("lang"), reader.elementText.take(GuideProgram.DESCRIPTION_LIMIT))
            "episode-num" -> episodeNumber(attribute("system")?.trim(), reader.elementText.trim())
            "category" -> category(reader.elementText.trim().take(GuideProgram.CATEGORY_LENGTH_LIMIT))
            "date" -> date(reader.elementText.take(SHORT_TEXT_LIMIT))
            "language" -> language = keepFirst(language, reader.elementText.trim().take(LANGUAGE_LIMIT))
            "rating" -> rating(attribute("system")?.take(SHORT_TEXT_LIMIT), ratingValue(reader))
            else -> return mark(name)
        }
        return true
    }

    fun program() = GuideProgram(
        "", Instant.EPOCH, Instant.EPOCH, title, description, subTitle, numbers, icon, categories, rating, isNew,
        previouslyShown, date,
        titleLanguage ?: descriptionLanguage ?: language,
    )

    /** Notes the empty marker children; none of them, nor any unknown child, is read through its end tag here. */
    private fun mark(name: String): Boolean {
        when (name) {
            "icon" -> icon = icon ?: normalizeLogoUrl(attribute("src"), baseUrl)

            "new" -> isNew = true

            "previously-shown" ->
                previouslyShown =
                    previouslyShown ?: PreviouslyShown(attribute("start")?.take(SHORT_TEXT_LIMIT))
        }
        return false
    }

    private fun title(lang: String?, text: String) {
        if (title.isNotBlank()) return
        title = text
        titleLanguage = lang
    }

    private fun description(lang: String?, text: String) {
        if (description != null) return
        description = text
        descriptionLanguage = lang
    }

    private fun episodeNumber(system: String?, text: String) {
        if (numbers.size < GuideProgram.EPISODE_NUMBER_LIMIT && text.isNotEmpty() &&
            text.length <= GuideProgram.EPISODE_VALUE_LIMIT
        ) {
            val scheme = system?.ifEmpty { null } ?: "onscreen"
            numbers += EpisodeNumber(scheme.take(GuideProgram.EPISODE_VALUE_LIMIT), text)
        }
    }

    private fun date(text: String) {
        if (date == null) date = text
    }

    private fun category(text: String) {
        if (categories.size < GuideProgram.CATEGORY_LIMIT && text.isNotEmpty()) categories += text
    }

    private fun rating(system: String?, value: String?) {
        if (rating == null && value != null) rating = ProgramRating(value, system)
    }

    private fun attribute(name: String): String? = reader.getAttributeValue(null, name)
}

/**
 * Calls [child] with the name of each direct child of the current element, up to its end tag. [child] answers whether
 * it read that child through its end tag; a child it did not is skipped whole.
 */
private inline fun XMLStreamReader.forEachChild(child: (String) -> Boolean) {
    var depth = 0
    while (hasNext()) {
        when (next()) {
            XMLStreamConstants.END_ELEMENT -> if (depth == 0) return else depth--
            XMLStreamConstants.START_ELEMENT -> if (depth > 0 || !child(localName)) depth++
        }
    }
}

/** The text of a `<rating>`'s first direct `<value>` child, reading the rating through its end tag. */
private fun ratingValue(reader: XMLStreamReader): String? {
    var value: String? = null
    reader.forEachChild { child ->
        val first = child == "value" && value == null
        if (first) value = reader.elementText.trim().take(SHORT_TEXT_LIMIT).ifEmpty { null }
        first
    }
    return value
}

/** [kept] once there is one, else [next] unless it is empty. */
private fun keepFirst(kept: String?, next: String): String? = kept ?: next.ifEmpty { null }

private fun xmlTime(value: String?): Instant? = try {
    OffsetDateTime.parse(value?.trim(), XMLTV_TIME).toInstant()
} catch (_: Exception) {
    null
}
