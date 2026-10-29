package dev.jamesrich.sofaking.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.jamesrich.sofaking.data.SettingsKeys.ADS_DODGED_DAY
import dev.jamesrich.sofaking.data.SettingsKeys.ADS_DODGED_MS
import dev.jamesrich.sofaking.data.SettingsKeys.API_TOKEN
import dev.jamesrich.sofaking.data.SettingsKeys.APPEARANCE
import dev.jamesrich.sofaking.data.SettingsKeys.AUTO_OPEN_DELAY
import dev.jamesrich.sofaking.data.SettingsKeys.AUTO_SKIP_INTROS
import dev.jamesrich.sofaking.data.SettingsKeys.BUFFER
import dev.jamesrich.sofaking.data.SettingsKeys.CAPTIONS_ENABLED
import dev.jamesrich.sofaking.data.SettingsKeys.CAPTION_ADVANCE_MS
import dev.jamesrich.sofaking.data.SettingsKeys.CAPTION_STYLE
import dev.jamesrich.sofaking.data.SettingsKeys.CHANNEL_CAPTION_ADVANCE_MS
import dev.jamesrich.sofaking.data.SettingsKeys.CHANNEL_DECODER
import dev.jamesrich.sofaking.data.SettingsKeys.CLIENT_ID
import dev.jamesrich.sofaking.data.SettingsKeys.DECODER
import dev.jamesrich.sofaking.data.SettingsKeys.FAVORITES
import dev.jamesrich.sofaking.data.SettingsKeys.FAVORITES_ORDER
import dev.jamesrich.sofaking.data.SettingsKeys.GUIDE_DENSITY
import dev.jamesrich.sofaking.data.SettingsKeys.GUIDE_FILTER
import dev.jamesrich.sofaking.data.SettingsKeys.HIDDEN_CHANNELS
import dev.jamesrich.sofaking.data.SettingsKeys.HIDE_SPOILERS
import dev.jamesrich.sofaking.data.SettingsKeys.HOUSEHOLD_SYNCED_AT
import dev.jamesrich.sofaking.data.SettingsKeys.KEY_LEGEND_SHOWN
import dev.jamesrich.sofaking.data.SettingsKeys.KIDS_MODE
import dev.jamesrich.sofaking.data.SettingsKeys.LAST_CHANNEL
import dev.jamesrich.sofaking.data.SettingsKeys.LAST_TAB
import dev.jamesrich.sofaking.data.SettingsKeys.LIVE_BREAKS
import dev.jamesrich.sofaking.data.SettingsKeys.MATCH_FRAME_RATE
import dev.jamesrich.sofaking.data.SettingsKeys.NIGHT_LISTENING
import dev.jamesrich.sofaking.data.SettingsKeys.PREFERENCES_SYNCED_AT
import dev.jamesrich.sofaking.data.SettingsKeys.PREVIEW_SOUND
import dev.jamesrich.sofaking.data.SettingsKeys.RECENT_CHANNELS
import dev.jamesrich.sofaking.data.SettingsKeys.SEEK_BACK_SECONDS
import dev.jamesrich.sofaking.data.SettingsKeys.SEEK_FORWARD_SECONDS
import dev.jamesrich.sofaking.data.SettingsKeys.SEND_CRASH_REPORTS
import dev.jamesrich.sofaking.data.SettingsKeys.SEPARATOR
import dev.jamesrich.sofaking.data.SettingsKeys.SERVER_URL
import dev.jamesrich.sofaking.data.SettingsKeys.SETTINGS_PIN
import dev.jamesrich.sofaking.data.SettingsKeys.SHARE_FAVORITES
import dev.jamesrich.sofaking.data.SettingsKeys.SKIP_COMMERCIALS
import dev.jamesrich.sofaking.data.SettingsKeys.SKIP_COMMERCIAL_BREAKS
import dev.jamesrich.sofaking.data.SettingsKeys.SKIP_CREDITS
import dev.jamesrich.sofaking.data.SettingsKeys.SKIP_INTROS
import dev.jamesrich.sofaking.data.SettingsKeys.SMOOTH_CAPTIONS
import dev.jamesrich.sofaking.data.SettingsKeys.STARTUP
import dev.jamesrich.sofaking.data.SettingsKeys.STARTUP_CHANNEL
import dev.jamesrich.sofaking.data.SettingsKeys.SUBTITLE_BOX
import dev.jamesrich.sofaking.data.SettingsKeys.SUBTITLE_RAISE
import dev.jamesrich.sofaking.data.SettingsKeys.SUBTITLE_SIZE
import dev.jamesrich.sofaking.data.SettingsKeys.TRACK_CHOICES
import dev.jamesrich.sofaking.data.SettingsKeys.VOLUME
import dev.jamesrich.sofaking.data.SettingsKeys.VOLUME_MUTED
import dev.jamesrich.sofaking.data.SettingsKeys.ZAP_SKIPS_HIDDEN
import kotlinx.coroutines.flow.first
import kotlin.enums.enumEntries

/** The preferences keys the settings are stored under, and the separator their packed values use. */
internal object SettingsKeys {
    val SERVER_URL = stringPreferencesKey("backend_url")
    val API_TOKEN = stringPreferencesKey("api_token")
    val FAVORITES = stringSetPreferencesKey("favorites")
    val LAST_CHANNEL = stringPreferencesKey("last_channel")
    val RECENT_CHANNELS = stringPreferencesKey("recent_channels")
    val GUIDE_FILTER = stringPreferencesKey("guide_filter")
    val CAPTIONS_ENABLED = booleanPreferencesKey("captions_enabled")
    val CAPTION_ADVANCE_MS = intPreferencesKey("caption_advance_ms")
    val CHANNEL_CAPTION_ADVANCE_MS = stringSetPreferencesKey("channel_caption_advance_ms")
    val SMOOTH_CAPTIONS = booleanPreferencesKey("smooth_captions")
    val CAPTION_STYLE = stringPreferencesKey("caption_style")
    val AUTO_OPEN_DELAY = stringPreferencesKey("auto_open_delay")
    val PREVIEW_SOUND = booleanPreferencesKey("preview_sound")
    val VOLUME = intPreferencesKey("volume")
    val VOLUME_MUTED = booleanPreferencesKey("volume_muted")
    val KEY_LEGEND_SHOWN = intPreferencesKey("key_legend_shown")
    val STARTUP = stringPreferencesKey("startup")
    val STARTUP_CHANNEL = stringPreferencesKey("startup_channel")
    val LAST_TAB = stringPreferencesKey("last_tab")
    val GUIDE_DENSITY = stringPreferencesKey("guide_density")
    val APPEARANCE = stringPreferencesKey("appearance")
    val HIDDEN_CHANNELS = stringSetPreferencesKey("hidden_channels")
    val ZAP_SKIPS_HIDDEN = booleanPreferencesKey("zap_skips_hidden")

    /** Before [SKIP_COMMERCIAL_BREAKS]; still written for an older build on the same device. */
    val SKIP_COMMERCIALS = booleanPreferencesKey("skip_commercials")

    /** Before [SKIP_INTROS]; still written for an older build on the same device. */
    val AUTO_SKIP_INTROS = booleanPreferencesKey("auto_skip_intros")
    val SKIP_INTROS = stringPreferencesKey("skip_intros")
    val SKIP_CREDITS = stringPreferencesKey("skip_credits")
    val SKIP_COMMERCIAL_BREAKS = stringPreferencesKey("skip_commercial_breaks")
    val SEEK_BACK_SECONDS = intPreferencesKey("seek_back_seconds")
    val SEEK_FORWARD_SECONDS = intPreferencesKey("seek_forward_seconds")
    val HIDE_SPOILERS = booleanPreferencesKey("hide_spoilers")
    val SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
    val SUBTITLE_RAISE = stringPreferencesKey("subtitle_raise")
    val SUBTITLE_BOX = booleanPreferencesKey("subtitle_box")
    val LIVE_BREAKS = stringPreferencesKey("live_breaks")
    val ADS_DODGED_DAY = stringPreferencesKey("ads_dodged_day")
    val ADS_DODGED_MS = longPreferencesKey("ads_dodged_ms")
    val FAVORITES_ORDER = stringPreferencesKey("favorites_order")
    val TRACK_CHOICES = stringPreferencesKey("track_choices")
    val MATCH_FRAME_RATE = booleanPreferencesKey("match_frame_rate")
    val NIGHT_LISTENING = booleanPreferencesKey("night_listening")
    val DECODER = stringPreferencesKey("decoder")
    val CHANNEL_DECODER = stringSetPreferencesKey("channel_decoder")
    val BUFFER = stringPreferencesKey("buffer")
    val CLIENT_ID = stringPreferencesKey("client_id")
    val PREFERENCES_SYNCED_AT = stringPreferencesKey("preferences_synced_at")
    val SETTINGS_PIN = stringPreferencesKey("settings_pin")
    val KIDS_MODE = booleanPreferencesKey("kids_mode")
    val SHARE_FAVORITES = booleanPreferencesKey("share_favorites")
    val HOUSEHOLD_SYNCED_AT = stringPreferencesKey("household_synced_at")
    val SEND_CRASH_REPORTS = booleanPreferencesKey("send_crash_reports")
    const val SEPARATOR = '\t'
}

/** The settings as [prefs] holds them; a value missing or from another build reads as its default. */
internal fun readSettings(prefs: Preferences): AppSettings = AppSettings(
    serverUrl = prefs[SERVER_URL].orEmpty(),
    apiToken = prefs[API_TOKEN].orEmpty(),
    favorites = prefs[FAVORITES] ?: emptySet(),
    lastChannel = prefs[LAST_CHANNEL],
    recentChannels = recentChannels(prefs),
    guideFilter = prefs[GUIDE_FILTER],
    captionsEnabled = prefs[CAPTIONS_ENABLED] ?: false,
    captionAdvanceMs = advanceMs(prefs),
    captionAdvanceByChannel = channelAdvances(prefs),
    smoothCaptions = prefs[SMOOTH_CAPTIONS] ?: true,
    captionStyle = prefs[CAPTION_STYLE].toEnum(CaptionStyle.SYSTEM),
    autoOpenDelay = prefs[AUTO_OPEN_DELAY].toEnum(AutoOpenDelay.AFTER_10_SECONDS),
    previewSound = prefs[PREVIEW_SOUND] ?: true,
    volume = (prefs[VOLUME] ?: AppSettings.MAX_VOLUME).coerceIn(0, AppSettings.MAX_VOLUME),
    volumeMuted = prefs[VOLUME_MUTED] ?: false,
    keyLegendShown = prefs[KEY_LEGEND_SHOWN] ?: 0,
    // A stored LAST_CHANNEL, from before the tabs were remembered, reads as RESUME.
    startup = prefs[STARTUP].toEnum(StartupTarget.RESUME),
    startupChannel = prefs[STARTUP_CHANNEL],
    lastTab = prefs[LAST_TAB].toEnum(TopLevelTab.LIVE),
    guideDensity = prefs[GUIDE_DENSITY].toEnum(GuideDensity.COMFORTABLE),
    appearance = prefs[APPEARANCE].toEnum(Appearance.SYSTEM),
    hiddenChannels = prefs[HIDDEN_CHANNELS] ?: emptySet(),
    zapSkipsHidden = prefs[ZAP_SKIPS_HIDDEN] ?: false,
    skips = readSkips(prefs),
    seekSteps = SeekSteps(
        prefs[SEEK_BACK_SECONDS] ?: SeekSteps.DEFAULT_BACK_SECONDS,
        prefs[SEEK_FORWARD_SECONDS] ?: SeekSteps.DEFAULT_FORWARD_SECONDS,
    ),
    hideSpoilers = prefs[HIDE_SPOILERS] ?: false,
    subtitleLook = readSubtitleLook(prefs),
    liveBreaks = prefs[LIVE_BREAKS].toEnum(LiveBreaks.PLAY),
    adsDodged = AdsDodged(prefs[ADS_DODGED_DAY].orEmpty(), prefs[ADS_DODGED_MS] ?: 0L),
    trackChoices = trackChoices(prefs),
    favoritesOrder = favoritesOrder(prefs).distinct(),
    playback = readPlayback(prefs),
    clientId = prefs[CLIENT_ID].orEmpty(),
    preferencesSyncedAt = prefs[PREFERENCES_SYNCED_AT].orEmpty(),
    settingsPin = prefs[SETTINGS_PIN].orEmpty(),
    // Written only while a PIN is set, and removed with it.
    kidsMode = prefs[KIDS_MODE] == true,
    shareFavorites = prefs[SHARE_FAVORITES] == true,
    householdSyncedAt = prefs[HOUSEHOLD_SYNCED_AT].orEmpty(),
    // On until switched off, so a value missing reads as on.
    sendCrashReports = prefs[SEND_CRASH_REPORTS] != false,
)

/** The skip modes; until one is set, the switch an older build kept stands in for it (on is automatic). */
internal fun readSkips(prefs: Preferences): Skips {
    fun fromSwitch(on: Boolean?) = if (on == true) SkipMode.AUTO else SkipMode.BUTTON
    return Skips(
        intros = prefs[SKIP_INTROS].toEnum(fromSwitch(prefs[AUTO_SKIP_INTROS])),
        credits = prefs[SKIP_CREDITS].toEnum(SkipMode.BUTTON),
        commercialBreaks = prefs[SKIP_COMMERCIAL_BREAKS].toEnum(fromSwitch(prefs[SKIP_COMMERCIALS])),
    )
}

internal fun readSubtitleLook(prefs: Preferences): SubtitleLook = SubtitleLook(
    size = prefs[SUBTITLE_SIZE].toEnum(SubtitleSize.MEDIUM),
    raise = prefs[SUBTITLE_RAISE].toEnum(SubtitleRaise.NONE),
    box = prefs[SUBTITLE_BOX] ?: true,
)

internal fun readPlayback(prefs: Preferences): PlaybackPreferences = PlaybackPreferences(
    matchFrameRate = prefs[MATCH_FRAME_RATE] ?: false,
    decoder = prefs[DECODER].toEnum(DecoderPreference.AUTO),
    decoderByChannel = channelDecoders(prefs),
    buffer = prefs[BUFFER].toEnum(BufferPreference.STANDARD),
    nightListening = prefs[NIGHT_LISTENING] ?: false,
)

internal fun trackChoices(prefs: Preferences): Map<String, TrackChoice> =
    prefs[TRACK_CHOICES].orEmpty().lines().mapNotNull { line ->
        val id = line.substringBefore(SEPARATOR, "").ifEmpty { return@mapNotNull null }
        TrackChoice.decode(line.substringAfter(SEPARATOR))?.let { id to it }
    }.toMap()

internal fun favoritesOrder(prefs: Preferences): List<String> =
    prefs[FAVORITES_ORDER].orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }

/** Entries are `guideId<TAB>DECODER`; anything malformed or unknown is skipped. */
internal fun channelDecoders(prefs: Preferences): Map<String, DecoderPreference> =
    prefs[CHANNEL_DECODER].orEmpty().mapNotNull { entry ->
        val id = entry.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")
        val value = DecoderPreference.entries.firstOrNull { it.name == entry.substringAfterLast(SEPARATOR) }
        if (id.isEmpty() || value == null) null else id to value
    }.toMap()

/** A stored enum name, or [default] when the value is missing or from a build that no longer has it. */
internal inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    enumEntries<T>().firstOrNull { it.name == this } ?: default

internal fun advanceMs(prefs: Preferences): Int =
    (prefs[CAPTION_ADVANCE_MS] ?: AppSettings.DEFAULT_CAPTION_ADVANCE_MS).coerceIn(
        AppSettings.CAPTION_ADVANCE_RANGE_MS,
    )

/** Newest first, joined by tabs, since a preferences string set has no order. */
internal fun recentChannels(prefs: Preferences): List<String> =
    prefs[RECENT_CHANNELS].orEmpty().split(SEPARATOR).filter {
        it.isNotEmpty()
    }.distinct().take(AppSettings.RECENT_CHANNELS)

/** Entries are `guideId<TAB>ms`; anything malformed is skipped. */
internal fun channelAdvances(prefs: Preferences): Map<String, Int> =
    prefs[CHANNEL_CAPTION_ADVANCE_MS].orEmpty().mapNotNull { entry ->
        val id = entry.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")
        val value = entry.substringAfterLast(SEPARATOR).toIntOrNull()
        if (id.isEmpty() || value == null) null else id to value.coerceIn(AppSettings.CAPTION_ADVANCE_RANGE_MS)
    }.toMap()
