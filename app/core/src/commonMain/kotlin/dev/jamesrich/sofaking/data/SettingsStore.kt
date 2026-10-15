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
    /** The channels watched most recently, newest first, at most [RECENT_CHANNELS]; the guide's Recent chip lists them. */
    val recentChannels: List<String> = emptyList(),
    /** The guide filter last chosen, as the guide keys it (see `GuideFilter.key`), or null for All; reopened on launch. */
    val guideFilter: String? = null,
    val captionsEnabled: Boolean = false,
    /** How many milliseconds ahead captions render, to offset live captioning delay; negative delays them instead. */
    val captionAdvanceMs: Int = DEFAULT_CAPTION_ADVANCE_MS,
    /** Per-channel overrides of [captionAdvanceMs], keyed by [Channel.guideId]. */
    val captionAdvanceByChannel: Map<String, Int> = emptyMap(),
    /** Holds roll-up caption text until a line completes; see `SmoothingTextOutput`. */
    val smoothCaptions: Boolean = true,
    val captionStyle: CaptionStyle = CaptionStyle.SYSTEM,
    /** How long focus rests on a guide channel before its preview opens full screen; OFF never does. */
    val autoOpenDelay: AutoOpenDelay = AutoOpenDelay.AFTER_10_SECONDS,
    /** Whether a resting guide preview unmutes; off keeps it silent until full screen. */
    val previewSound: Boolean = true,
    /** How many info bars have carried the key legend; it stops after [KEY_LEGEND_BARS]. */
    val keyLegendShown: Int = 0,
) {
    fun captionAdvanceFor(guideId: String): Int = captionAdvanceByChannel[guideId] ?: captionAdvanceMs

    /** Keeps the token out of logs and crash reports; everything else prints as a data class would. */
    override fun toString(): String =
        "AppSettings(serverUrl=$serverUrl, apiToken=${if (apiToken.isEmpty()) "unset" else "set"}, favorites=$favorites, " +
            "lastChannel=$lastChannel, recentChannels=$recentChannels, guideFilter=$guideFilter, captionsEnabled=$captionsEnabled, " +
            "captionAdvanceMs=$captionAdvanceMs, " +
            "captionAdvanceByChannel=$captionAdvanceByChannel, smoothCaptions=$smoothCaptions, captionStyle=$captionStyle, " +
            "autoOpenDelay=$autoOpenDelay, previewSound=$previewSound, keyLegendShown=$keyLegendShown)"

    companion object {
        const val DEFAULT_CAPTION_ADVANCE_MS = 0
        /** Negative shows captions later, positive earlier; the per-channel values keep the same range. */
        val CAPTION_ADVANCE_RANGE_MS = -8_000..8_000
        const val CAPTION_ADVANCE_STEP_MS = 500

        /** The key legend rides the first info bars after install, then only the stream card. */
        const val KEY_LEGEND_BARS = 3

        /** How many recently watched channels are kept. */
        const val RECENT_CHANNELS = 10

        /** [recent] with [guideId] moved to the front, trimmed to [RECENT_CHANNELS]. */
        fun recentWith(recent: List<String>, guideId: String): List<String> =
            (listOf(guideId) + recent.filterNot { it == guideId }).take(RECENT_CHANNELS)
    }
}

/** How captions are drawn; SYSTEM defers to the platform's CaptioningManager settings. */
enum class CaptionStyle { SYSTEM, LARGE_HIGH_CONTRAST }

/** [afterMs] is how long focus rests before the preview opens full screen, or null when it never does. */
enum class AutoOpenDelay(val afterMs: Long?) {
    OFF(null),
    AFTER_10_SECONDS(10_000L),
    AFTER_30_SECONDS(30_000L),
}

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun setServer(serverUrl: String, apiToken: String)

    suspend fun toggleFavorite(guideId: String)

    /** Records [guideId] as the channel being watched: the last channel, and the front of the recent list. */
    suspend fun setLastChannel(guideId: String)

    /** Keeps the guide filter the viewer chose, by its key, or null for All. */
    suspend fun setGuideFilter(key: String?)

    suspend fun setCaptionsEnabled(enabled: Boolean)

    suspend fun setCaptionAdvanceMs(ms: Int)

    suspend fun setChannelCaptionAdvance(guideId: String, ms: Int)

    suspend fun setSmoothCaptions(enabled: Boolean)

    suspend fun setCaptionStyle(style: CaptionStyle)

    suspend fun setAutoOpenDelay(delay: AutoOpenDelay)

    suspend fun setPreviewSound(enabled: Boolean)

    suspend fun setKeyLegendShown(count: Int)
}
