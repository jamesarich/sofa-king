package dev.jamesrich.sofaking.data

import kotlinx.coroutines.flow.Flow

data class AppSettings(
    /** The Sofa King server's base address; empty until the user enters one. */
    val serverUrl: String = "",
    /** The server's bearer token, sent on API requests; empty when the server runs without one. */
    val apiToken: String = "",
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

    /** Keeps the token out of logs and crash reports; everything else prints as a data class would. */
    override fun toString(): String =
        "AppSettings(serverUrl=$serverUrl, apiToken=${if (apiToken.isEmpty()) "unset" else "set"}, favorites=$favorites, " +
            "lastChannel=$lastChannel, captionsEnabled=$captionsEnabled, captionAdvanceMs=$captionAdvanceMs, " +
            "captionAdvanceByChannel=$captionAdvanceByChannel, smoothCaptions=$smoothCaptions, captionStyle=$captionStyle)"

    companion object {
        const val DEFAULT_CAPTION_ADVANCE_MS = 0
        val CAPTION_ADVANCE_RANGE_MS = 0..8_000
        const val CAPTION_ADVANCE_STEP_MS = 500
    }
}

/** How captions are drawn; SYSTEM defers to the platform's CaptioningManager settings. */
enum class CaptionStyle { SYSTEM, LARGE_HIGH_CONTRAST }

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun setServer(serverUrl: String, apiToken: String)

    suspend fun toggleFavorite(guideId: String)

    suspend fun setLastChannel(guideId: String)

    suspend fun setCaptionsEnabled(enabled: Boolean)

    suspend fun setCaptionAdvanceMs(ms: Int)

    suspend fun setChannelCaptionAdvance(guideId: String, ms: Int)

    suspend fun setSmoothCaptions(enabled: Boolean)

    suspend fun setCaptionStyle(style: CaptionStyle)
}
