package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.catalog.normalizedName
import dev.jamesrich.sofaking.backend.providers.array
import dev.jamesrich.sofaking.backend.providers.get
import dev.jamesrich.sofaking.backend.providers.httpOrNull
import dev.jamesrich.sofaking.backend.providers.str
import dev.jamesrich.sofaking.backend.providers.string
import dev.jamesrich.sofaking.backend.providers.truthy
import kotlinx.serialization.json.JsonElement

/** One channel of the iptv-org database: [id] is like `CNN.us`, [closed] is true once it has a `closed` date. */
class IptvChannel(
    val id: String,
    val name: String,
    val altNames: List<String>,
    val country: String?,
    val categories: List<String>,
    val nsfw: Boolean,
    val closed: Boolean,
) {
    /** The name with the loose normalization the lineup policy's station keys use. */
    val normalized: String = normalizedName(name, IptvOrgData.NAME_VERSION)
}

/** A channel the community database asks clients not to list, with the [reason] and the issue or page it cites. */
class IptvBlock(val channel: String, val reason: String?, val ref: String?)

/** One stream of a channel; [referrer] and [userAgent] are the headers the stream's host wants. */
class IptvStream(
    val channel: String,
    val feed: String?,
    val title: String?,
    val url: String,
    val referrer: String?,
    val userAgent: String?,
    val quality: String?,
)

/** The parsed iptv-org files the server holds. */
class IptvOrgData(
    val channels: List<IptvChannel>,
    /** The best logo URL per channel id. */
    val logos: Map<String, String>,
    val blocklist: Map<String, IptvBlock>,
    /** The first country of each IANA time zone id. */
    val timezones: Map<String, String>,
    val streams: List<IptvStream>,
) {
    val byId: Map<String, IptvChannel> = channels.associateBy { it.id }
    val byName: Map<String, List<IptvChannel>> = channels.groupBy { it.normalized }.filterKeys { it.isNotEmpty() }
    val byAltName: Map<String, List<IptvChannel>> = channels.flatMap { channel ->
        channel.altNames.map { normalizedName(it, IptvOrgData.NAME_VERSION) to channel }
    }.filter { it.first.isNotEmpty() }.groupBy({ it.first }, { it.second })
    val streamsById: Map<String, IptvStream> = streams.associateBy { streamId(it) }

    companion object {
        /** Names compare without spaces, as station keys do by default. */
        const val NAME_VERSION = 2

        val EMPTY = IptvOrgData(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList())

        /** The id a stream's provider channel carries: its channel, then a short digest of its URL. */
        fun streamId(stream: IptvStream): String =
            "${stream.channel}#${Integer.toHexString(stream.url.hashCode()).padStart(HEX_LENGTH, '0')}"

        private const val HEX_LENGTH = 8
    }
}

internal fun parseChannels(rows: List<JsonElement>): List<IptvChannel> = rows.mapNotNull { row ->
    val id = row.str("id") ?: return@mapNotNull null
    val name = row.str("name") ?: return@mapNotNull null
    IptvChannel(
        id,
        name,
        row["alt_names"].array.mapNotNull { it.string },
        row.str("country")?.uppercase(),
        row["categories"].array.mapNotNull { it.string },
        row["is_nsfw"].truthy,
        row.str("closed") != null,
    )
}

private class Logo(val channel: String, val url: String, val score: Long)

/** The best logo of each channel: one in use beats one that is not, then the larger wins, then the first listed. */
internal fun parseLogos(rows: List<JsonElement>): Map<String, String> = rows.mapNotNull { row ->
    val channel = row.str("channel")
    val url = httpOrNull(row.str("url"))
    val area = (row["width"].string?.toLongOrNull() ?: 0L) * (row["height"].string?.toLongOrNull() ?: 0L)
    if (channel == null ||
        url == null
    ) {
        null
    } else {
        Logo(channel, url, (if (row["in_use"].truthy) IN_USE_BONUS else 0L) + area)
    }
}.groupBy { it.channel }.mapValues { (_, logos) -> logos.maxBy { it.score }.url }

internal fun parseBlocklist(rows: List<JsonElement>): Map<String, IptvBlock> = rows.mapNotNull { row ->
    val channel = row.str("channel") ?: return@mapNotNull null
    IptvBlock(channel, row.str("reason"), row.str("ref"))
}.associateBy { it.channel }

internal fun parseTimezones(rows: List<JsonElement>): Map<String, String> = rows.mapNotNull { row ->
    val id = row.str("id") ?: return@mapNotNull null
    val country = row["countries"].array.firstNotNullOfOrNull { it.string } ?: return@mapNotNull null
    id to country.uppercase()
}.toMap()

internal fun parseStreams(rows: List<JsonElement>): List<IptvStream> = rows.mapNotNull { row ->
    val channel = row.str("channel") ?: return@mapNotNull null
    val url = httpOrNull(row.str("url")) ?: return@mapNotNull null
    IptvStream(
        channel,
        row.str("feed"),
        row.str("title"),
        url,
        row.str("referrer"),
        row.str("user_agent"),
        row.str("quality"),
    )
}

private const val IN_USE_BONUS = 1L shl 40
