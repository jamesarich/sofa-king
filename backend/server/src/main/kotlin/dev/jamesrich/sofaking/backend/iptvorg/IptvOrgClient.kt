package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.providers.ProviderChannel
import dev.jamesrich.sofaking.backend.providers.ProviderClient
import dev.jamesrich.sofaking.backend.providers.ProviderContext
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ResolvedStream

/**
 * The iptv-org streams as a provider: one channel per stream of the downloaded `streams.json`, for the channels of the
 * region in force, leaving out blocklisted, closed and NSFW ones. It lists nothing while streams are off, and each
 * stream's referrer and user agent go out as the request headers its host wants. Nothing here touches the network:
 * [IptvOrgCatalog] downloads the lists, and the relay, probes and remux fetch the stream URLs like any source.
 */
class IptvOrgClient(private val catalog: IptvOrgCatalog, private val settings: () -> IptvOrgSettings) :
    ProviderClient {
    override val service: String get() = SERVICE

    override suspend fun channels(): List<ProviderChannel> {
        if (!settings().streams) return emptyList()
        val data = catalog.data
        val countries = catalog.region().countries.toSet()
        return data.streams.mapNotNull { stream ->
            data.byId[stream.channel]?.takeIf { it.importable(countries, data) }?.let { channel ->
                ProviderChannel(
                    id = IptvOrgData.streamId(stream),
                    name = channel.name,
                    logo = data.logos[channel.id],
                    group = channel.categories.firstOrNull()?.replaceFirstChar(Char::uppercase),
                    tags = listOfNotNull(channel.country?.lowercase(), stream.quality),
                )
            }
        }
    }

    /** In one of the [countries], open, safe for work and not on the blocklist. */
    private fun IptvChannel.importable(countries: Set<String>, data: IptvOrgData): Boolean =
        country in countries && !closed && !nsfw && id !in data.blocklist

    override suspend fun resolve(channelId: String): ResolvedStream {
        if (!settings().streams) throw ProviderFailure("iptv-org streams are off")
        val stream = catalog.data.streamsById[channelId]
            ?: throw ProviderFailure("Channel is no longer listed", transient = false)
        val headers = buildMap {
            stream.referrer?.let { put("Referer", it) }
            stream.userAgent?.let { put("User-Agent", it) }
        }
        return ResolvedStream(stream.url, headers)
    }

    companion object {
        const val SERVICE = "iptv-org"

        /** The client's entry for the provider registry's `local` clients. */
        fun local(
            catalog: IptvOrgCatalog,
            settings: () -> IptvOrgSettings,
        ): Pair<String, (ProviderContext) -> ProviderClient> = SERVICE to { _ -> IptvOrgClient(catalog, settings) }

        /** The providers the feed service shows nowhere: this one, while streams are off. */
        fun hiddenWhileOff(settings: () -> IptvOrgSettings): () -> Set<String> =
            { if (settings().streams) emptySet() else setOf(SERVICE) }
    }
}
