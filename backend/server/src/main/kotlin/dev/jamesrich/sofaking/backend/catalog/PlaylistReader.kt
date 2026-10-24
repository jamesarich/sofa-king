package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.contract.Channel
import java.util.UUID

private const val PLAYLIST_CHANNEL_LIMIT = 10_000
private const val PLAYLIST_OFFERING_LIMIT = 20_000

/** How much of a channel id stands in for a channel number the entry does not give. */
private const val FALLBACK_NUMBER_LENGTH = 8

private val ATTRIBUTE = Regex("([\\w-]+)=\"([^\"]*)\"")
private val PROTOCOLS = setOf("hls", "mpegts")
private val DUPLICATE = PlaylistEntry.Skipped("duplicate-source")

/** One `#EXTINF` entry and its URL, read into a channel and its offering, or the reason the import skips it. */
private sealed interface PlaylistEntry {
    class Skipped(val reason: String) : PlaylistEntry

    /** [published] says the entry named its channel's identity; without one the URL is the identity. */
    class Read(val channel: Channel, val offering: Offering, val published: Boolean) : PlaylistEntry
}

/**
 * Reads an extended M3U playlist of [provider] into channels and offerings, skipping the malformed entries.
 * [defaultProtocol] stands in for an entry's missing `stream-type`, [renewable] names the query parameters a source's
 * identity ignores, and relative `tvg-logo` references resolve against [baseUrl].
 */
internal class PlaylistReader(
    private val provider: String,
    private val defaultProtocol: String?,
    private val renewable: Set<String>,
    private val baseUrl: String?,
) {
    private val channels = linkedMapOf<String, Channel>()
    private val offerings = mutableListOf<Offering>()
    private val offeringIds = HashSet<String>()
    private val skipped = sortedMapOf<String, Int>()
    private val notes = sortedMapOf<String, Int>()

    fun read(text: String): ParsedPlaylist {
        require(text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) { "Not an extended M3U playlist" }
        var info: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> info = line

                line.isBlank() || line.startsWith('#') -> Unit

                else -> {
                    add(info?.let { entry(it, line) } ?: PlaylistEntry.Skipped("no-extinf"))
                    info = null
                }
            }
        }
        return ParsedPlaylist(channels.values.toList(), offerings, skipped, notes)
    }

    private fun add(entry: PlaylistEntry) {
        when (entry) {
            is PlaylistEntry.Skipped -> skipped.merge(entry.reason, 1, Int::plus)
            is PlaylistEntry.Read -> if (offeringIds.add(entry.offering.id)) keep(entry) else add(DUPLICATE)
        }
    }

    private fun keep(entry: PlaylistEntry.Read) {
        val channel = entry.channel
        if (!entry.published) notes.merge("url-identity", 1, Int::plus)
        // Multiple URLs with one exact provider key are alternatives of the first entry, never fuzzy name merges.
        if (channels[channel.id]?.let { it.name == channel.name && it.displayNumber == channel.displayNumber } ==
            false
        ) {
            notes.merge("merged-key", 1, Int::plus)
        }
        channels.putIfAbsent(channel.id, channel)
        offerings += entry.offering
        require(channels.size <= PLAYLIST_CHANNEL_LIMIT && offerings.size <= PLAYLIST_OFFERING_LIMIT) {
            "Playlist exceeds import limits"
        }
    }

    /** The entry whose `#EXTINF` line is [metadata] and whose URL is [line]. */
    private fun entry(metadata: String, line: String): PlaylistEntry {
        val uri = try {
            httpUri(line)
        } catch (_: Exception) {
            null
        } ?: return PlaylistEntry.Skipped("invalid-url")
        val attributes = ATTRIBUTE.findAll(metadata).associate { it.groupValues[1] to it.groupValues[2] }
        val name = name(metadata)?.ifBlank { attributes["tvg-name"].orEmpty() }.orEmpty()
        val protocol = attributes["stream-type"] ?: defaultProtocol
            ?: if (uri.path.endsWith(".m3u8", true)) "hls" else "mpegts"
        return when {
            name.isBlank() -> PlaylistEntry.Skipped("missing-name")
            protocol !in PROTOCOLS -> PlaylistEntry.Skipped("bad-protocol")
            else -> identified(attributes, name, line, protocol)
        }
    }

    /** The entry's channel, keyed by the identity it publishes or else by its URL. */
    private fun identified(
        attributes: Map<String, String>,
        name: String,
        line: String,
        protocol: String,
    ): PlaylistEntry {
        val sourceKey = attributes["stream-id"] ?: checkNotNull(sourceKey(line, renewable))
        val published = attributes["channel-id"] ?: attributes["tvg-id"]?.takeIf { it.isNotBlank() }
            ?: attributes["channel-uuid"]
        // An entry without a published identity is its own channel, keyed by its URL; the name is display only.
        val key = published ?: "url\u0000$sourceKey"
        val id = try {
            attributes["channel-uuid"]?.let { UUID.fromString(it).toString() } ?: stableId("channel:$provider:$key")
        } catch (_: IllegalArgumentException) {
            return PlaylistEntry.Skipped("invalid-uuid")
        }
        val channel = Channel(
            id,
            attributes["tvg-chno"] ?: attributes["channel-number"] ?: published ?: id.take(FALLBACK_NUMBER_LENGTH),
            name,
            normalizeLogoUrl(attributes["tvg-logo"], baseUrl),
            attributes["group-title"]?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
            attributes["tvg-id"],
        )
        val offering = Offering(stableId("offering:$provider:$key:$sourceKey"), id, provider, line, protocol)
        return PlaylistEntry.Read(channel, offering, published != null)
    }

    /** The name after the comma outside quoted attributes, so commas in station names survive; null without one. */
    private fun name(metadata: String): String? {
        var quoted = false
        val split = metadata.indexOfFirst { c ->
            if (c == '"') quoted = !quoted
            c == ',' && !quoted
        }
        return if (split < 0) null else metadata.substring(split + 1).trim()
    }
}
