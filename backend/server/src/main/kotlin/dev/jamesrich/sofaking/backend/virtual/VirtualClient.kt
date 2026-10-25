package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.providers.ProviderChannel
import dev.jamesrich.sofaking.backend.providers.ProviderClient
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ResolvedStream

/**
 * The `virtual` service: the virtual channels as one provider, so the lineup, the guide, the outputs and the DVR treat
 * them as any other source. Each resolves to its stream on the server's own loopback route, which [urlOf] gives.
 */
class VirtualClient(private val channels: VirtualChannelService, private val urlOf: suspend (String) -> String) :
    ProviderClient {
    override val service: String get() = SERVICE

    override suspend fun channels(): List<ProviderChannel> =
        channels.channels().map { ProviderChannel(it.id, it.name, it.number, group = GROUP) }

    override suspend fun guide(wanted: Set<String>): ParsedGuide = ParsedGuide(
        channels.channels().map { GuideChannel(it.id, it.name) },
        channels.programs(wanted),
    )

    override suspend fun resolve(channelId: String): ResolvedStream {
        if (channels.channels().none { it.id == channelId }) {
            throw ProviderFailure("No such virtual channel", transient = false)
        }
        return ResolvedStream(urlOf(channelId))
    }

    companion object {
        const val SERVICE = "virtual"
        const val GROUP = "Virtual"
    }
}
