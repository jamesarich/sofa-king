package dev.jamesrich.sofaking.data

import kotlinx.coroutines.flow.Flow

data class AppSettings(
    val serverUrl: String,
    val profile: String,
    /** [Channel.guideId]s the user starred. */
    val favorites: Set<String> = emptySet(),
    /** [Channel.guideId] of the channel watched most recently, to reopen on launch. */
    val lastChannel: String? = null,
    val captionsEnabled: Boolean = false,
    /** How many seconds ahead captions render, to offset live captioning delay. */
    val captionAdvanceSeconds: Int = DEFAULT_CAPTION_ADVANCE_SECONDS,
) {
    companion object {
        const val DEFAULT_CAPTION_ADVANCE_SECONDS = 3
        val CAPTION_ADVANCE_RANGE = 0..6
    }
}

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun setServer(serverUrl: String, profile: String)

    suspend fun toggleFavorite(guideId: String)

    suspend fun setLastChannel(guideId: String)

    suspend fun setCaptionsEnabled(enabled: Boolean)

    suspend fun setCaptionAdvanceSeconds(seconds: Int)
}
