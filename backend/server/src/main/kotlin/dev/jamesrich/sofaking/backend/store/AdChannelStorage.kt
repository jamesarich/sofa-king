package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.playback.AdCensus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/** A channel's row of [AdChannels]. */
data class AdChannelRecord(
    val channelId: String,
    val providerId: String,
    val watchedMs: Long,
    val lastWatchedAt: Instant?,
    val breaks: Int,
    val learned: Int,
    val markedAt: Instant?,
    val listenedMs: Long,
    val lastListenedAt: Instant?,
)

/** Every channel the sessions or the listener have played. */
suspend fun BackendStore.adChannels(): List<AdChannelRecord> = read { AdChannels.selectAll().map { it.adChannel() } }

/**
 * Adds what one play of [channelId] on [providerId] found, ending at [now], to the channel's totals: a viewer's when
 * [listened] is false, the listener's when it is true.
 */
suspend fun BackendStore.recordAdChannel(
    channelId: String,
    providerId: String,
    census: AdCensus,
    now: Instant,
    listened: Boolean = false,
) = query {
    val current = AdChannels.selectAll().where { AdChannels.channelId eq channelId }.singleOrNull()?.adChannel()
    val markedAt = if (census.marked) now.toString() else current?.markedAt?.toString()
    if (current == null) {
        AdChannels.insert {
            it[AdChannels.channelId] = channelId
            it[AdChannels.providerId] = providerId
            it[watchedMs] = if (listened) 0 else census.playedMs
            it[lastWatchedAt] = if (listened) null else now.toString()
            it[breaks] = census.breaks
            it[learned] = census.learned
            it[AdChannels.markedAt] = markedAt
            it[listenedMs] = if (listened) census.playedMs else 0
            it[lastListenedAt] = if (listened) now.toString() else null
        }
    } else {
        AdChannels.update({ AdChannels.channelId eq channelId }) {
            it[AdChannels.providerId] = providerId
            if (listened) {
                it[listenedMs] = current.listenedMs + census.playedMs
                it[lastListenedAt] = now.toString()
            } else {
                it[watchedMs] = current.watchedMs + census.playedMs
                it[lastWatchedAt] = now.toString()
            }
            it[breaks] = current.breaks + census.breaks
            it[learned] = current.learned + census.learned
            it[AdChannels.markedAt] = markedAt
        }
    }
    Unit
}

private fun ResultRow.adChannel() = AdChannelRecord(
    this[AdChannels.channelId], this[AdChannels.providerId], this[AdChannels.watchedMs],
    this[AdChannels.lastWatchedAt]?.let(
        Instant::parse,
    ),
    this[AdChannels.breaks], this[AdChannels.learned],
    this[AdChannels.markedAt]?.let(
        Instant::parse,
    ),
    this[AdChannels.listenedMs],
    this[AdChannels.lastListenedAt]?.let(Instant::parse),
)
