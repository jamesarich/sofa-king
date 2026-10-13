package dev.jamesrich.sofaking.backend.providers

import java.time.Duration

/**
 * Samsung TV Plus through Matt Huisman's published mirrors: the channel list from i.mjh.nz (gzip JSON, by
 * region), the XMLTV from the same host as an ordinary guide, and a `jmp2.uk/stvp-{id}` redirect per channel
 * whose target carries `{PSID}`-style ad macros. Channels with a `license_url` are DRM and left out.
 */
class SamsungClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "samsung"

    private val channelsUrl = context.options["channelsUrl"] ?: CHANNELS_URL
    private val streamBase = context.options["streamBase"] ?: STREAM_BASE
    private val region = (context.options["region"] ?: "us").lowercase()

    override suspend fun channels(): List<ProviderChannel> = listed()

    override suspend fun resolve(channelId: String): ResolvedStream {
        if (listed().none { it.id == channelId }) {
            context.invalidate(CHANNELS)
            if (listed().none { it.id == channelId }) throw ProviderFailure("Channel is no longer listed", transient = false)
        }
        val hop = context.http.get("$streamBase$channelId", HEADERS, follow = false)
        val target = when {
            hop.status in 300..399 && hop.location != null -> hop.location
            hop.ok && hop.text().trimStart().startsWith("#EXTM3U") -> hop.url
            hop.status == 404 || hop.status == 410 -> throw ProviderFailure("Samsung redirect answered HTTP ${hop.status}", transient = false)
            else -> throw ProviderFailure("Samsung redirect answered HTTP ${hop.status}")
        }
        val now = context.clock.instant()
        val url = Macros.fill(target, mapOf("PSID" to context.deviceId(), "TARGETOPT" to "0", "US_PRIVACY" to "1---"))
        return ResolvedStream(url, HEADERS, now.plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<ProviderChannel> = context.cached(CHANNELS, Duration.ofMinutes(30)) { fetchChannels() }

    private suspend fun fetchChannels(): List<ProviderChannel> {
        val payload = context.http.get(channelsUrl, HEADERS).require("Samsung channel list").json()
        val channels = (payload["regions"][region] ?: throw ProviderFailure("Samsung region $region is not published", transient = false))["channels"].obj
            ?: throw ProviderFailure("Samsung channel list has no channels")
        val result = channels.entries.mapNotNull { (id, channel) ->
            if (channel.str("license_url") != null) return@mapNotNull null
            val name = channel.str("name") ?: id
            ProviderChannel(id, name, channel["chno"].long?.toString(), httpOrNull(channel.str("logo")), channel.str("group"), listOf(region))
        }.sortedBy { it.number?.toLongOrNull() ?: Long.MAX_VALUE }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result
    }

    companion object {
        private const val CHANNELS_URL = "https://i.mjh.nz/SamsungTVPlus/.channels.json.gz"
        private const val STREAM_BASE = "https://jmp2.uk/stvp-"
        private const val CHANNELS = "channels"
        private val URL_LIFETIME = Duration.ofHours(1)
        private val HEADERS = mapOf("User-Agent" to "okhttp/4.12.0")
    }
}
