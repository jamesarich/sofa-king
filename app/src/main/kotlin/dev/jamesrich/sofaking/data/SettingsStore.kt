package dev.jamesrich.sofaking.data

import kotlinx.coroutines.flow.Flow

data class AppSettings(
    val serverUrl: String,
    val profile: String,
    /** [Channel.guideId]s the user starred. */
    val favorites: Set<String> = emptySet(),
    /** [Channel.guideId] of the channel watched most recently, to reopen on launch. */
    val lastChannel: String? = null,
)

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun setServer(serverUrl: String, profile: String)

    suspend fun toggleFavorite(guideId: String)

    suspend fun setLastChannel(guideId: String)
}
