package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * STIRR's live channels as stirr.com uses them: one `videos/list` document of every live channel, the `epg` endpoint
 * without a channel for the whole guide (only channels with programs are listed), and a `playable` POST per resolve that answers an ad-stitched HLS URL carrying a
 * short-lived `vx_token`. Channels marked `drm_protected` are left out.
 */
class StirrClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "stirr"

    private val apiBase = context.options["apiBase"] ?: API_BASE

    override suspend fun channels(): List<ProviderChannel> = listed()

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed().associateBy { it.id }
        val rows = context.http.get("$apiBase/epg?tz=UTC", HEADERS).require("STIRR guide").json()["data"]["channels"].array
        val index = ArrayList<GuideChannel>()
        val now = context.clock.instant()
        val programs = ArrayList<GuideProgram>()
        for (row in rows) {
            val id = row["channel_id"].long?.toString() ?: continue
            val channel = channels[id] ?: continue
            val items = row["programs"].array
            if (items.isEmpty()) continue
            index += GuideChannel(id, channel.name, channel.logo)
            if (id !in wanted) continue
            for (item in items) {
                val start = item["start_time"].long ?: continue
                val end = item["end_time"].long ?: continue
                val title = item.str("title") ?: continue
                if (end <= start || Instant.ofEpochSecond(end) < now) continue
                programs += GuideProgram(id, Instant.ofEpochSecond(start), Instant.ofEpochSecond(end), title, item.str("description"))
            }
        }
        return ParsedGuide(index, programs)
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        if (listed().none { it.id == channelId }) {
            context.invalidate(CHANNELS)
            if (listed().none { it.id == channelId }) throw ProviderFailure("Channel is no longer listed", transient = false)
        }
        val answer = context.http.post("$apiBase/v2/videos/$channelId/playable", HEADERS, body = "").require("STIRR playable").json()
        val media = answer["data"][0]["media"].let { it[0].string ?: it.string } ?: throw ProviderFailure("STIRR answered no media", transient = false)
        val url = Macros.fill(media, emptyMap())
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw ProviderFailure("STIRR media is not a URL", transient = false)
        return ResolvedStream(url, STREAM_HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<ProviderChannel> = context.cached(CHANNELS, Duration.ofMinutes(30)) { fetchChannels() }

    private suspend fun fetchChannels(): List<ProviderChannel> {
        val payload = context.http.get("$apiBase/videos/list/?categories=all_categories&content_type=4&no_limit=true", HEADERS).require("STIRR channel list").json()
        val rows = ArrayList<JsonObject>()
        collect(payload["videos"], rows)
        val result = LinkedHashMap<String, ProviderChannel>()
        for (row in rows) {
            val id = row.str("videoid") ?: continue
            val name = row.str("title")?.replace(SPACES, " ") ?: continue
            if (row.str("drm_protected")?.lowercase() == "yes" || row.str("active") == "no" || result.containsKey(id)) continue
            val logo = httpOrNull(row.str("logo")) ?: httpOrNull(row["square_thumbs"].str("300x300"))
            val number = row["channel_number"].long?.takeIf { it > 0 }?.toString()
            result[id] = ProviderChannel(id, name, number, logo, row["categories"][0].str("category_name"), listOf("us"))
        }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result.values.sortedBy { it.number?.toLongOrNull() ?: Long.MAX_VALUE }
    }

    /** The list nests its rows in arrays of arrays by category; a row is any object with a `videoid` and a `title`. */
    private fun collect(element: JsonElement?, into: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> if (element["videoid"] != null && element["title"] != null) into += element else element.values.forEach { collect(it, into) }
            is JsonArray -> element.forEach { collect(it, into) }
            else -> Unit
        }
    }

    companion object {
        private const val API_BASE = "https://stirr.com/api"
        private const val CHANNELS = "channels"
        private val SPACES = Regex("""\s+""")
        private val URL_LIFETIME = Duration.ofHours(1)
        private val STREAM_HEADERS = mapOf("User-Agent" to BROWSER_USER_AGENT, "Origin" to "https://stirr.com", "Referer" to "https://stirr.com/")
        private val HEADERS = STREAM_HEADERS + ("Accept" to "application/json, text/plain, */*")
    }
}
