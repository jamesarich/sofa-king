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
    /**
     * The channels watched most recently, newest first, at most [RECENT_CHANNELS]; the guide's Recent chip lists them.
     */
    val recentChannels: List<String> = emptyList(),
    /**
     * The guide filter last chosen, as the guide keys it (see `GuideFilter.key`), or null for All; reopened on launch.
     */
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
    /**
     * The players' own volume in percent, and whether it is muted, where the app draws a volume control (desktop and
     * web); Android and Apple TV follow the system volume and leave these alone. Never synced.
     */
    val volume: Int = MAX_VOLUME,
    val volumeMuted: Boolean = false,
    /** How many info bars have carried the key legend; it stops after [KEY_LEGEND_BARS]. */
    val keyLegendShown: Int = 0,
    /** Where a cold start lands; [startupChannel] is the channel for [StartupTarget.CHANNEL]. */
    val startup: StartupTarget = StartupTarget.RESUME,
    val startupChannel: String? = null,
    /** The top-level tab the app was last on, which [StartupTarget.RESUME] reopens; never synced. */
    val lastTab: TopLevelTab = TopLevelTab.LIVE,
    val guideDensity: GuideDensity = GuideDensity.COMFORTABLE,
    /** [Channel.guideId]s the guide and channel list leave out on this device; the server's lineup is untouched. */
    val hiddenChannels: Set<String> = emptySet(),
    /** Whether Channel Up and Down and the D-pad skip [hiddenChannels] too, or only the lists leave them out. */
    val zapSkipsHidden: Boolean = false,
    /** How the players skip intros, end credits and a recording's commercial breaks. */
    val skips: Skips = Skips(),
    /** How far one press back or forward moves a library item or a recording. */
    val seekSteps: SeekSteps = SeekSteps(),
    /** Whether an episode not started yet keeps its overview back and its still blurred ([hidesSpoilers]). */
    val hideSpoilers: Boolean = false,
    /** How a library item's text subtitles look; live and recording captions follow [captionStyle]. */
    val subtitleLook: SubtitleLook = SubtitleLook(),
    /**
     * What the live player does in an ad break a streaming channel marks: plays it, or covers it with the break screen.
     */
    val liveBreaks: LiveBreaks = LiveBreaks.PLAY,
    /** How long the break screen has covered ads today on this device; never synced. */
    val adsDodged: AdsDodged = AdsDodged(),
    /**
     * The favorites in the order the user put them (see [orderedFavorites]); starred
     * channels not listed follow by number.
     */
    val favoritesOrder: List<String> = emptyList(),
    val playback: PlaybackPreferences = PlaybackPreferences(),
    /**
     * This installation's id on the server, under which it keeps the viewer's preferences; made once by
     * [SettingsStore.ensureClientId].
     */
    val clientId: String = "",
    /**
     * The `updatedAt` of the server's preferences document this device last pushed or took over; empty before any sync.
     */
    val preferencesSyncedAt: String = "",
    /** The hash of the PIN that locks Settings (see [SettingsPin]), empty while there is no lock; never synced. */
    val settingsPin: String = "",
    /** The tracks picked in each show, by show id, most recent first; never synced. */
    val trackChoices: Map<String, TrackChoice> = emptyMap(),
    /** Whether [KidsMode] is on; it is only while [settingsPin] is set, which leaves it. Never synced. */
    val kidsMode: Boolean = false,
    /** Whether this device keeps its favorites and hidden channels in step with the household's shared copy. */
    val shareFavorites: Boolean = false,
    /** The `updatedAt` of the household's shared copy this device last pushed or took over; empty before any. */
    val householdSyncedAt: String = "",
    /**
     * Whether a crash the app kept is sent to the configured server at the next start. The report goes to that server
     * only, never to anyone else, and holds no address, token, channel or title.
     */
    val sendCrashReports: Boolean = true,
) {
    /** True while Settings asks for a PIN before it opens. */
    val settingsLocked: Boolean get() = settingsPin.isNotEmpty()

    fun captionAdvanceFor(guideId: String): Int = captionAdvanceByChannel[guideId] ?: captionAdvanceMs

    /** Keeps the token out of logs and crash reports; everything else prints as a data class would. */
    override fun toString(): String =
        "AppSettings(serverUrl=$serverUrl, apiToken=${if (apiToken.isEmpty()) "unset" else "set"}, " +
            "favorites=$favorites, " +
            "lastChannel=$lastChannel, recentChannels=$recentChannels, guideFilter=$guideFilter, " +
            "captionsEnabled=$captionsEnabled, captionAdvanceMs=$captionAdvanceMs, " +
            "captionAdvanceByChannel=$captionAdvanceByChannel, smoothCaptions=$smoothCaptions, " +
            "captionStyle=$captionStyle, " +
            "autoOpenDelay=$autoOpenDelay, previewSound=$previewSound, volume=$volume, volumeMuted=$volumeMuted, " +
            "keyLegendShown=$keyLegendShown, startup=$startup, " +
            "startupChannel=$startupChannel, lastTab=$lastTab, guideDensity=$guideDensity, " +
            "hiddenChannels=$hiddenChannels, " +
            "zapSkipsHidden=$zapSkipsHidden, skips=$skips, seekSteps=$seekSteps, " +
            "hideSpoilers=$hideSpoilers, subtitleLook=$subtitleLook, liveBreaks=$liveBreaks, adsDodged=$adsDodged, " +
            "favoritesOrder=$favoritesOrder, playback=$playback, clientId=$clientId, " +
            "preferencesSyncedAt=$preferencesSyncedAt, settingsPin=${if (settingsPin.isEmpty()) "unset" else "set"}, " +
            "trackChoices=${trackChoices.size}, kidsMode=$kidsMode, shareFavorites=$shareFavorites, " +
            "householdSyncedAt=$householdSyncedAt, sendCrashReports=$sendCrashReports)"

    companion object {
        const val DEFAULT_CAPTION_ADVANCE_MS = 0

        /** Negative shows captions later, positive earlier; the per-channel values keep the same range. */
        val CAPTION_ADVANCE_RANGE_MS = -8_000..8_000
        const val CAPTION_ADVANCE_STEP_MS = 500

        /** [volume] runs from silent to the stream's own level; the players never amplify. */
        const val MAX_VOLUME = 100

        /** The key legend rides the first info bars after install, then only the stream card. */
        const val KEY_LEGEND_BARS = 3

        /** How many recently watched channels are kept. */
        const val RECENT_CHANNELS = 10

        /** [recent] with [guideId] moved to the front, trimmed to [RECENT_CHANNELS]. */
        fun recentWith(recent: List<String>, guideId: String): List<String> =
            (listOf(guideId) + recent.filterNot { it == guideId }).take(RECENT_CHANNELS)
    }
}

/**
 * What a cold start opens: where the viewer left off ([AppSettings.lastTab], with the channel watched last full
 * screen on Live), the guide, the library, or one chosen channel.
 */
enum class StartupTarget { RESUME, GUIDE, LIBRARY, CHANNEL }

/** The app's top-level tabs, as the settings remember the one last open. */
enum class TopLevelTab { LIVE, LIBRARY, RECORDINGS }

/** How big a library item's text subtitles are, as a share of the normal size. */
enum class SubtitleSize(val scale: Float) { SMALL(scale = 0.8f), MEDIUM(scale = 1f), LARGE(scale = 1.3f) }

/** How high a library item's text subtitles sit: at the bottom, or raised clear of a letterbox's bar or a ticker. */
enum class SubtitleRaise { NONE, LOW, HIGH }

/** A library item's text subtitles: their [size], how far they are [raise]d, and whether a [box] sits behind them. */
data class SubtitleLook(
    val size: SubtitleSize = SubtitleSize.MEDIUM,
    val raise: SubtitleRaise = SubtitleRaise.NONE,
    val box: Boolean = true,
)

/** How captions are drawn; SYSTEM defers to the platform's CaptioningManager settings. */
enum class CaptionStyle { SYSTEM, LARGE_HIGH_CONTRAST }

/**
 * How a player meets a part it can skip: [AUTO] skips it after a countdown on its button, [BUTTON] offers the button
 * and waits for a press, [OFF] offers nothing.
 */
enum class SkipMode { AUTO, BUTTON, OFF }

/** What can be skipped: an episode's intro, its end credits, and a recording's commercial breaks. */
enum class SkipKind { INTROS, CREDITS, COMMERCIAL_BREAKS }

/** The [SkipMode] for each [SkipKind]. */
data class Skips(
    val intros: SkipMode = SkipMode.BUTTON,
    val credits: SkipMode = SkipMode.BUTTON,
    val commercialBreaks: SkipMode = SkipMode.BUTTON,
) {
    fun of(kind: SkipKind): SkipMode = when (kind) {
        SkipKind.INTROS -> intros
        SkipKind.CREDITS -> credits
        SkipKind.COMMERCIAL_BREAKS -> commercialBreaks
    }
}

/** How far one press back and one press forward move a library item or a recording, in seconds. */
data class SeekSteps(val backSeconds: Int = DEFAULT_BACK_SECONDS, val forwardSeconds: Int = DEFAULT_FORWARD_SECONDS) {
    val backMs: Long get() = backSeconds * MS_PER_SECOND
    val forwardMs: Long get() = forwardSeconds * MS_PER_SECOND

    companion object {
        const val DEFAULT_BACK_SECONDS = 10
        const val DEFAULT_FORWARD_SECONDS = 30

        /** The steps Settings offers, each way. */
        val CHOICES = listOf(5, 10, 15, 20, 30, 45, 60, 90)
        private const val MS_PER_SECOND = 1_000L
    }
}

/**
 * The live player in an ad break: [PLAY] shows it; the others cover it with the break
 * screen, with or without its sound.
 */
enum class LiveBreaks(val covers: Boolean, val muted: Boolean) {
    PLAY(covers = false, muted = false),
    SCREEN_MUTED(covers = true, muted = true),
    SCREEN(covers = true, muted = false),
}

/**
 * The milliseconds of ads the break screen covered on [day], a local date as `2026-10-22` that the caller supplies;
 * another day reads as none, so the count starts again each morning.
 */
data class AdsDodged(val day: String = "", val ms: Long = 0) {
    /** The milliseconds covered on [day]. */
    fun on(day: String): Long = if (day == this.day) ms else 0

    /** This count with [ms] more covered on [day], which starts it again when the day has changed. */
    fun plus(day: String, ms: Long): AdsDodged = AdsDodged(day, on(day) + ms.coerceAtLeast(0))
}

/** [afterMs] is how long focus rests before the preview opens full screen, or null when it never does. */
enum class AutoOpenDelay(val afterMs: Long?) {
    OFF(afterMs = null),
    AFTER_10_SECONDS(afterMs = 10_000L),
    AFTER_30_SECONDS(afterMs = 30_000L),
}

/** The app's stored settings, read as one [settings] flow and written through each area's setters. */
interface SettingsStore :
    ChannelSettings,
    CaptionSettings,
    PlayerSettings,
    DisplaySettings,
    DeviceSettings {
    val settings: Flow<AppSettings>
}

/**
 * The channels the viewer keeps: favorites and their order, hidden channels, the recent list, the guide filter and
 * where the app starts.
 */
interface ChannelSettings {
    suspend fun toggleFavorite(guideId: String)

    /** Records [guideId] as the channel being watched: the last channel, and the front of the recent list. */
    suspend fun setLastChannel(guideId: String)

    /** Keeps the guide filter the viewer chose, by its key, or null for All. */
    suspend fun setGuideFilter(key: String?)

    suspend fun setChannelHidden(guideId: String, hidden: Boolean)

    /** Hides or shows every channel in [guideIds] in one write, for a group's switch. */
    suspend fun setChannelsHidden(guideIds: Collection<String>, hidden: Boolean)

    suspend fun setZapSkipsHidden(enabled: Boolean)

    suspend fun setFavoritesOrder(order: List<String>)

    /** [guideId] is kept only for [StartupTarget.CHANNEL]; the other targets clear it. */
    suspend fun setStartup(target: StartupTarget, guideId: String? = null)

    /** Remembers [tab] as the one the app was last on. */
    suspend fun setLastTab(tab: TopLevelTab)
}

/**
 * Captions on live TV and subtitles on library items: on or off, their timing, style and look, and the tracks picked
 * per show.
 */
interface CaptionSettings {
    suspend fun setCaptionsEnabled(enabled: Boolean)

    suspend fun setCaptionAdvanceMs(ms: Int)

    suspend fun setChannelCaptionAdvance(guideId: String, ms: Int)

    suspend fun setSmoothCaptions(enabled: Boolean)

    suspend fun setCaptionStyle(style: CaptionStyle)

    suspend fun setSubtitleLook(look: SubtitleLook)

    /** Keeps [choice] as the tracks picked in the show [showId]. */
    suspend fun setTrackChoice(showId: String, choice: TrackChoice)
}

/** How the players play: volume, decoder, buffer and frame rate, and what they skip (commercials, intros, breaks). */
interface PlayerSettings {
    /** Keeps the players' [level], 0 to [AppSettings.MAX_VOLUME], and whether it is [muted]. */
    suspend fun setVolume(level: Int, muted: Boolean)

    suspend fun setMatchFrameRate(enabled: Boolean)

    suspend fun setNightListening(enabled: Boolean)

    suspend fun setDecoder(decoder: DecoderPreference)

    /** A per-channel decoder, or null to follow the default again. */
    suspend fun setChannelDecoder(guideId: String, decoder: DecoderPreference?)

    suspend fun setBuffer(buffer: BufferPreference)

    /** How the players skip [kind]. */
    suspend fun setSkip(kind: SkipKind, mode: SkipMode)

    suspend fun setSeekSteps(steps: SeekSteps)

    suspend fun setLiveBreaks(mode: LiveBreaks)

    /** Adds [ms] of ads the break screen covered on [day] to [AppSettings.adsDodged]. */
    suspend fun addAdsDodged(day: String, ms: Long)
}

/** How the app looks around the player: the guide's preview, clock format, density, the key legend and spoilers. */
interface DisplaySettings {
    suspend fun setAutoOpenDelay(delay: AutoOpenDelay)

    suspend fun setPreviewSound(enabled: Boolean)

    suspend fun setKeyLegendShown(count: Int)

    suspend fun setGuideDensity(density: GuideDensity)

    suspend fun setHideSpoilers(enabled: Boolean)
}

/** This device's own: the server it pairs with, its client id, the preferences sync, the Settings PIN and kids mode. */
interface DeviceSettings {
    suspend fun setServer(serverUrl: String, apiToken: String)

    /** The stored client id, generating and storing one on the first call. */
    suspend fun ensureClientId(): String

    /**
     * Takes over the server's copy of the synced preferences: [hidden] replaces the hidden set, [favorites] the
     * starred set and their order, [recent] the recent list (its first entry becomes the last channel when the
     * device has none), and [syncedAt] is remembered as the document taken.
     */
    suspend fun applyServerPreferences(
        hidden: Set<String>,
        favorites: List<String>,
        recent: List<String>,
        syncedAt: String,
    )

    /** Remembers the server document's stamp after this device pushed its preferences. */
    suspend fun setPreferencesSyncedAt(syncedAt: String)

    /** Locks Settings behind [hash], a [SettingsPin.hash], or removes the lock, and kids mode with it, for null. */
    suspend fun setSettingsPin(hash: String?)

    /** Turns [AppSettings.kidsMode] on, which takes a PIN already set, or off. */
    suspend fun setKidsMode(on: Boolean)

    /** Turns [AppSettings.shareFavorites] on or off. */
    suspend fun setShareFavorites(on: Boolean)

    /** Turns [AppSettings.sendCrashReports] on or off. */
    suspend fun setSendCrashReports(on: Boolean)

    /**
     * Takes over the household's shared copy: [hidden] replaces the hidden set and [favorites] the starred set and
     * their order, and [syncedAt] is remembered as the document taken.
     */
    suspend fun applyHouseholdPreferences(hidden: Set<String>, favorites: List<String>, syncedAt: String)

    /** Remembers the household document's stamp after this device pushed to it. */
    suspend fun setHouseholdSyncedAt(syncedAt: String)
}
