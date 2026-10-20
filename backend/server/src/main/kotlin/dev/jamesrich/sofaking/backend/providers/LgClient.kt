package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.zip.Inflater
import kotlinx.serialization.json.JsonElement

/**
 * LG Channels as lgchannels.com reads it: one `schedulelist` document, served as base64 of a zlib stream under
 * `text/plain`, carrying every category, channel, static HLS template (`[DEVICE_ID]`-style macros) and the next
 * hours of programmes. The device headers are what the web client sends with its country and language.
 */
class LgClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "lg"

    private val apiBase = context.options["apiBase"] ?: API_BASE

    private class Listed(val channel: ProviderChannel, val template: String, val programmes: List<GuideProgramme>)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        return ParsedGuide(channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }, channels.filter { it.channel.id in wanted }.flatMap { it.programmes })
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val template = listed().firstOrNull { it.channel.id == channelId }?.template
            ?: run { context.invalidate(SCHEDULE); listed().firstOrNull { it.channel.id == channelId }?.template }
            ?: throw ProviderFailure("Channel is no longer listed", transient = false)
        val now = context.clock.instant()
        val url = Macros.fill(template, MACROS + mapOf("DEVICE_ID" to context.deviceId(), "NONCE" to now.epochSecond.toString(), "UA" to BROWSER_USER_AGENT))
        return ResolvedStream(url, STREAM_HEADERS, now.plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<Listed> = context.cached(SCHEDULE, Duration.ofMinutes(10)) { fetchSchedule() }

    private suspend fun fetchSchedule(): List<Listed> {
        val fetched = context.http.get("$apiBase/schedulelist", API_HEADERS).require("LG schedulelist")
        val payload = decode(fetched.text())
        val result = LinkedHashMap<String, Listed>()
        for (category in payload["categories"].array) {
            val group = category.str("categoryName")
            for (channel in category["channels"].array) {
                val id = channel.str("channelId") ?: continue
                val name = channel.str("channelName") ?: continue
                val template = channel.str("mediaStaticUrl") ?: continue
                if (result.containsKey(id)) continue
                val programmes = channel["programs"].array.mapNotNull { programme ->
                    val start = programme.str("startDateTime")?.let(::instantOrNull) ?: return@mapNotNull null
                    val end = programme.str("endDateTime")?.let(::instantOrNull) ?: return@mapNotNull null
                    val title = programme.str("programTitle") ?: return@mapNotNull null
                    if (end <= start) null else GuideProgramme(id, start, end, title, programme.str("description"))
                }
                result[id] = Listed(
                    ProviderChannel(id, name, channel.str("channelNumber"), httpOrNull(channel.str("channelLogoUrl")), group ?: channel.str("channelGenreName"), listOf("us")),
                    template, programmes,
                )
            }
        }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result.values.toList()
    }


    companion object {
        private const val API_BASE = "https://api.lgchannels.com/api/v1.0"
        private const val SCHEDULE = "schedulelist"
        private val URL_LIFETIME = Duration.ofHours(1)
        private val STREAM_HEADERS = mapOf("User-Agent" to BROWSER_USER_AGENT, "Origin" to "https://lgchannels.com", "Referer" to "https://lgchannels.com/")
        private val API_HEADERS = STREAM_HEADERS + mapOf(
            "Accept" to "application/json, text/plain, */*", "x-device-country" to "US", "x-device-language" to "en", "x-device-type" to "WEB",
        )
        private val MACROS = mapOf(
            "LMT" to "0", "DNS" to "0", "IP" to "0.0.0.0", "COUNTRY" to "US", "APP_NAME" to "lgchannels_web", "DEVICE_TYPE" to "Personal Computer",
            "COPPA" to "0", "VIEWSIZE" to "1920x1080",
        )

        /** The document as JSON, or base64 of a zlib stream of JSON. */
        internal fun decode(text: String): JsonElement {
            val trimmed = text.trim()
            if (trimmed.startsWith("{")) return lenientJson.parseToJsonElement(trimmed)
            val compressed = try { Base64.getDecoder().decode(trimmed) } catch (e: IllegalArgumentException) { throw ProviderFailure("LG schedulelist is neither JSON nor base64", cause = e) }
            val inflater = Inflater()
            try {
                inflater.setInput(compressed)
                val out = java.io.ByteArrayOutputStream(compressed.size * 4)
                val buffer = ByteArray(65_536)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, count)
                    if (out.size() > ProviderHttp.DEFAULT_LIMIT) throw ProviderFailure("LG schedulelist is too large")
                }
                return lenientJson.parseToJsonElement(out.toString(Charsets.UTF_8.name()))
            } catch (e: java.util.zip.DataFormatException) {
                throw ProviderFailure("LG schedulelist did not inflate", cause = e)
            } finally {
                inflater.end()
            }
        }
    }
}
