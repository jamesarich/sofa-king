package dev.jamesrich.sofaking.backend.store

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
