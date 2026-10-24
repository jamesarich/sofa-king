package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.CatalogService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.eligible
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode

/** Which of a channel's sources plays: lineup order within each health rank. */
class SourceChoice internal constructor(private val catalog: CatalogService, private val store: BackendStore) {
    /** The source each channel would play now, for players that need the protocol before opening. */
    suspend fun preferredOfferings(channelIds: Collection<String>): Map<String, Offering> {
        val states = store.allHealth()
        return channelIds.mapNotNull { id ->
            catalog.offeringsFor(id)?.let { candidates -> choose(candidates, states) }?.let { id to it }
        }.toMap()
    }

    /** The first [count] of [channels], in order, whose chosen source [usable] accepts, each with that source. */
    suspend fun listenable(channels: List<String>, count: Int, usable: (Offering) -> Boolean): Map<String, Offering> {
        val states = store.allHealth()
        return channels.asSequence()
            .mapNotNull { channel ->
                catalog.offeringsFor(channel)?.let { choose(it, states) }?.takeIf(usable)?.let { channel to it }
            }
            .take(count.coerceAtLeast(0))
            .toMap(LinkedHashMap())
    }

    /** A healthy second choice beats an unverified first choice; sources in [except] are passed over. */
    internal suspend fun select(channelId: String, except: Set<String> = emptySet()): Offering {
        val candidates =
            catalog.offeringsFor(channelId)
                ?: throw ApiFailure(HttpStatusCode.NotFound, Wire.ErrorCode.CHANNEL_NOT_FOUND, "Unknown channel")
        return choose(candidates.filter { it.id !in except }, store.allHealth())
            ?: throw ApiFailure(HttpStatusCode.ServiceUnavailable, "SOURCE_UNAVAILABLE", "No eligible source", true)
    }

    internal fun choose(candidates: List<Offering>, states: Map<String, HealthState>): Offering? =
        candidates.sortedBy { selectionRank(states[it.id]?.status) }.firstOrNull {
            states[it.id]?.let(::eligible) == true
        }
}

/** Healthy first, then unverified, then suspect; anything else is never selected. */
internal fun selectionRank(status: HealthStatus?): Int = when (status) {
    HealthStatus.HEALTHY -> 0
    HealthStatus.UNVERIFIED -> 1
    HealthStatus.SUSPECT -> 2
    else -> Int.MAX_VALUE
}
