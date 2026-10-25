package dev.jamesrich.sofaking.backend.store

/** A watchlist row: a library item ([itemId]) or a guide title, on [channelId] only when set. */
data class StoredWatchlistEntry(
    val id: String,
    val kind: String,
    val itemId: String?,
    val title: String,
    val channelId: String?,
    val addedAt: String,
)
