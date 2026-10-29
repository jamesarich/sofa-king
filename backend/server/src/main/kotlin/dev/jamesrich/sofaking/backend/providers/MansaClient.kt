package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant

/**
 * Mansa, the free Black-culture service on the Kanku platform: one playlist call lists the channels with their
 * public HLS URLs (Wurl MediaTailor, Amagi or Ottera), and a shared feed carries about a day of airings. Entries
 * whose `channelType` is not `FAST` are on-demand playlists, not linear channels, and are left out. Wurl URLs carry
 * `{DEVICE_ID}`-style ad macros that [Macros] fills. The guide rows name their channel by the playlist's own
 * id; a row whose id is not listed falls back to its channel name with case and punctuation ignored.
 */
class MansaClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "mansa"

    private val playlistUrl = context.options["playlistUrl"] ?: PLAYLIST_URL
    private val guideUrl = context.options["guideUrl"] ?: GUIDE_URL

    private class Listed(val channel: ProviderChannel, val template: String)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { it.channel.toGuideChannel() }
        if (channels.none { it.channel.id in wanted }) return ParsedGuide(index, emptyList())
        val byId = channels.associate { it.channel.id to it.channel.id }
        val byName = channels.associate { normalized(it.channel.name) to it.channel.id }
        val earliest = context.clock.instant().minus(PAST_KEPT)
        val rows = context.http.get(guideUrl, HEADERS).require("Mansa guide").json()["items"].array
        val programs = rows.mapNotNull { row ->
            val channelId = row.str("channelId")?.let(byId::get)
                ?: row.str("channelName")?.let { byName[normalized(it)] }
            if (channelId == null || channelId !in wanted) return@mapNotNull null
            program(row, channelId)?.takeIf { it.endsAt > earliest }
        }
        return ParsedGuide(index, programs)
    }

    /** A guide row as a program on [channelId]; null when a field is missing. */
    private fun program(row: JsonElement, channelId: String): GuideProgram? {
        val start = row["startTimeMillis"].long?.let(Instant::ofEpochMilli)
        val end = row["endTimeMillis"].long?.let(Instant::ofEpochMilli)
        val title = row.str("title") ?: return null
        return guideProgram(channelId, start, end, title, row.str("description"))?.copy(
            icon = httpOrNull(row["images"][0].str("path")),
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val template = context.findListed(PLAYLIST) { listed().firstOrNull { it.channel.id == channelId }?.template }
        val url = Macros.fill(template, MACROS + ("DEVICE_ID" to context.deviceId()))
        return ResolvedStream(url, HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<Listed> = context.cached(PLAYLIST, CACHED_FOR) { fetchPlaylist() }

    private suspend fun fetchPlaylist(): List<Listed> {
        val payload = context.http.post(playlistUrl, HEADERS, REQUEST_BODY).require("Mansa playlist").json()
        val result = payload["channels"].array.mapNotNull(::listing).distinctBy { it.channel.id }
        requireChannels(result)
        return result
    }

    /** A linear channel with a stream template; null for an on-demand playlist or when a field is missing. */
    private fun listing(entry: JsonElement): Listed? {
        if (entry.str("channelType") != "FAST") return null
        val id = entry.str("id") ?: return null
        val name = entry.str("title") ?: return null
        val template = httpOrNull(entry.str("streamUrl")) ?: return null
        // Some entries put a URL in their tags where a genre belongs.
        val genre = entry["tags"].array.mapNotNull { it.string }.firstOrNull { !it.startsWith("http") }
        val logo = httpOrNull(entry.str("unSelectedStateImage")) ?: httpOrNull(entry.str("selectedStateImage"))
        return Listed(ProviderChannel(id, name, logo = logo, group = genre, tags = listOf("us")), template)
    }

    private fun normalized(name: String): String = name.lowercase().filter(Char::isLetterOrDigit)

    companion object {
        private const val PLAYLIST_URL = "https://prod.kankuapi.com/mansa/playlist/page"
        private const val GUIDE_URL = "https://prod.epgsyncservice.kankuapi.com/api/epgs"
        private const val PLAYLIST = "playlist"
        private const val REQUEST_BODY = """{"size":200}"""

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(10)
        private val URL_LIFETIME = Duration.ofHours(1)

        /** The feed keeps airings that ended long ago; the current one may have started an hour back or more. */
        private val PAST_KEPT = Duration.ofHours(1)
        private val HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Origin" to "https://www.mansa.com",
            "Referer" to "https://www.mansa.com/",
        )

        /** The privacy values the web player sends; `DEVICE_ID` is this install's. Unlisted macros fill empty. */
        private val MACROS = mapOf(
            "DNT" to "0", "US_PRIVACY" to "1---", "GDPR" to "0", "APP_NAME" to "Mansa",
            "APP_BUNDLE" to "www.mansa.com", "APP_STORE_URL" to "https://www.mansa.com/",
            "IFA_TYPE" to "dpid", "DEVICE_MAKE" to "web", "DEVICE_MODEL" to "web",
        )
    }
}
