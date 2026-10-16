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
    /** Where a cold start lands; [startupChannel] is the channel for [StartupTarget.CHANNEL]. */
    val startup: StartupTarget = StartupTarget.LAST_CHANNEL,
    val startupChannel: String? = null,
    val clockFormat: ClockFormat = ClockFormat.AUTO,
    val guideDensity: GuideDensity = GuideDensity.COMFORTABLE,
    /** [Channel.guideId]s the guide and channel list leave out on this device; the server's lineup is untouched. */
    val hiddenChannels: Set<String> = emptySet(),
    /** Whether Channel Up and Down and the D-pad skip [hiddenChannels] too, or only the lists leave them out. */
    val zapSkipsHidden: Boolean = false,
    /** The favorites in the order the user put them (see [orderedFavorites]); starred channels not listed follow by number. */
    val favoritesOrder: List<String> = emptyList(),
    val playback: PlaybackPreferences = PlaybackPreferences(),
    /** This installation's id on the server, under which it keeps the viewer's preferences; made once by [SettingsStore.ensureClientId]. */
    val clientId: String = "",
    /** The `updatedAt` of the server's preferences document this device last pushed or took over; empty before any sync. */
    val preferencesSyncedAt: String = "",
) {
    fun captionAdvanceFor(guideId: String): Int = captionAdvanceByChannel[guideId] ?: captionAdvanceMs

    fun isHidden(guideId: String): Boolean = guideId in hiddenChannels

    /** Keeps the token out of logs and crash reports; everything else prints as a data class would. */
    override fun toString(): String =
        "AppSettings(serverUrl=$serverUrl, apiToken=${if (apiToken.isEmpty()) "unset" else "set"}, favorites=$favorites, " +
            "lastChannel=$lastChannel, recentChannels=$recentChannels, guideFilter=$guideFilter, captionsEnabled=$captionsEnabled, " +
            "captionAdvanceMs=$captionAdvanceMs, " +
            "captionAdvanceByChannel=$captionAdvanceByChannel, smoothCaptions=$smoothCaptions, captionStyle=$captionStyle, " +
            "autoOpenDelay=$autoOpenDelay, previewSound=$previewSound, keyLegendShown=$keyLegendShown, startup=$startup, " +
            "startupChannel=$startupChannel, clockFormat=$clockFormat, guideDensity=$guideDensity, hiddenChannels=$hiddenChannels, " +
            "zapSkipsHidden=$zapSkipsHidden, favoritesOrder=$favoritesOrder, playback=$playback, clientId=$clientId, " +
            "preferencesSyncedAt=$preferencesSyncedAt)"

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

/** What a cold start opens: the channel watched last, the guide, or one chosen channel. */
enum class StartupTarget { LAST_CHANNEL, GUIDE, CHANNEL }

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

    /** [guideId] is kept only for [StartupTarget.CHANNEL]; the other targets clear it. */
    suspend fun setStartup(target: StartupTarget, guideId: String? = null)

    suspend fun setClockFormat(format: ClockFormat)

    suspend fun setGuideDensity(density: GuideDensity)

    suspend fun setChannelHidden(guideId: String, hidden: Boolean)

    /** Hides or shows every channel in [guideIds] in one write, for a group's switch. */
    suspend fun setChannelsHidden(guideIds: Collection<String>, hidden: Boolean)

    suspend fun setZapSkipsHidden(enabled: Boolean)

    suspend fun setFavoritesOrder(order: List<String>)

    suspend fun setMatchFrameRate(enabled: Boolean)

    suspend fun setDecoder(decoder: DecoderPreference)

    /** A per-channel decoder, or null to follow the default again. */
    suspend fun setChannelDecoder(guideId: String, decoder: DecoderPreference?)

    suspend fun setBuffer(buffer: BufferPreference)

    /** The stored client id, generating and storing one on the first call. */
    suspend fun ensureClientId(): String

    /**
     * Takes over the server's copy of the synced preferences: [hidden] replaces the hidden set, [favorites] the
     * starred set and their order, [recent] the recent list (its first entry becomes the last channel when the
     * device has none), and [syncedAt] is remembered as the document taken.
     */
    suspend fun applyServerPreferences(hidden: Set<String>, favorites: List<String>, recent: List<String>, syncedAt: String)

    /** Remembers the server document's stamp after this device pushed its preferences. */
    suspend fun setPreferencesSyncedAt(syncedAt: String)
}
