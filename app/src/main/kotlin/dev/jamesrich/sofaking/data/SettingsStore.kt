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
    /** How many milliseconds ahead captions render, to offset live captioning delay. */
    val captionAdvanceMs: Int = DEFAULT_CAPTION_ADVANCE_MS,
    /** Per-channel overrides of [captionAdvanceMs], keyed by [Channel.guideId]. */
    val captionAdvanceByChannel: Map<String, Int> = emptyMap(),
    /** Holds roll-up caption text until a line completes; see `SmoothingTextOutput`. */
    val smoothCaptions: Boolean = true,
    val captionStyle: CaptionStyle = CaptionStyle.SYSTEM,
) {
    fun captionAdvanceFor(guideId: String): Int = captionAdvanceByChannel[guideId] ?: captionAdvanceMs

    companion object {
        const val DEFAULT_CAPTION_ADVANCE_MS = 3_000
        val CAPTION_ADVANCE_RANGE_MS = 0..8_000
        const val CAPTION_ADVANCE_STEP_MS = 500
    }
}

/** How captions are drawn; SYSTEM defers to the platform's CaptioningManager settings. */
enum class CaptionStyle { SYSTEM, LARGE_HIGH_CONTRAST }

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun setServer(serverUrl: String, profile: String)

    suspend fun toggleFavorite(guideId: String)

    suspend fun setLastChannel(guideId: String)

    suspend fun setCaptionsEnabled(enabled: Boolean)

    suspend fun setCaptionAdvanceMs(ms: Int)

    suspend fun setChannelCaptionAdvance(guideId: String, ms: Int)

    suspend fun setSmoothCaptions(enabled: Boolean)

    suspend fun setCaptionStyle(style: CaptionStyle)
}
