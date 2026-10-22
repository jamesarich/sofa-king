package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlin.enums.enumEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import okio.Path.Companion.toPath

/** [SettingsStore] on a preferences [DataStore]; there must be only one per file, which the DI graph's single instance guarantees. */
class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
    /** How long an unreadable store waits before it is read again. */
    private val readRetry: Duration = 5.seconds,
) : SettingsStore {

    // An unreadable store reads as defaults rather than failing the flow, which would take the whole app down, and is
    // read again after a pause, so the settings return once it reads or a write replaces it.
    override val settings: Flow<AppSettings> = dataStore.data
        .retryWhen { cause, _ ->
            if (cause is CancellationException) return@retryWhen false
            emit(emptyPreferences())
            delay(readRetry)
            true
        }
        .map { prefs ->
            AppSettings(
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
                clockFormat = prefs[CLOCK_FORMAT].toEnum(ClockFormat.AUTO),
                guideDensity = prefs[GUIDE_DENSITY].toEnum(GuideDensity.COMFORTABLE),
                hiddenChannels = prefs[HIDDEN_CHANNELS] ?: emptySet(),
                zapSkipsHidden = prefs[ZAP_SKIPS_HIDDEN] ?: false,
                skipCommercials = prefs[SKIP_COMMERCIALS] ?: false,
                liveBreaks = prefs[LIVE_BREAKS].toEnum(LiveBreaks.PLAY),
                adsDodged = AdsDodged(prefs[ADS_DODGED_DAY].orEmpty(), prefs[ADS_DODGED_MS] ?: 0L),
                trackChoices = trackChoices(prefs),
                favoritesOrder = favoritesOrder(prefs).distinct(),
                playback = PlaybackPreferences(
                    matchFrameRate = prefs[MATCH_FRAME_RATE] ?: false,
                    decoder = prefs[DECODER].toEnum(DecoderPreference.AUTO),
                    decoderByChannel = channelDecoders(prefs),
                    buffer = prefs[BUFFER].toEnum(BufferPreference.STANDARD),
                ),
                clientId = prefs[CLIENT_ID].orEmpty(),
                preferencesSyncedAt = prefs[PREFERENCES_SYNCED_AT].orEmpty(),
                settingsPin = prefs[SETTINGS_PIN].orEmpty(),
            )
        }

    override suspend fun setServer(serverUrl: String, apiToken: String) {
        val server = ServerAddress.normalizeUrl(serverUrl) ?: serverUrl.trim().trimEnd('/')
        val token = ServerAddress.normalizeToken(apiToken) ?: apiToken.trim()
        dataStore.edit { prefs ->
            prefs[SERVER_URL] = server
            prefs[API_TOKEN] = token
        }
    }

    override suspend fun toggleFavorite(guideId: String) {
        dataStore.edit { prefs ->
            val current = prefs[FAVORITES] ?: emptySet()
            prefs[FAVORITES] = if (guideId in current) current - guideId else current + guideId
            // A channel unstarred loses its place; starred again, it joins the end.
            if (guideId in current) prefs[FAVORITES_ORDER] = favoritesOrder(prefs).filterNot { it == guideId }.joinToString(SEPARATOR.toString())
        }
    }

    override suspend fun setLastChannel(guideId: String) {
        dataStore.edit { prefs ->
            prefs[LAST_CHANNEL] = guideId
            prefs[RECENT_CHANNELS] = AppSettings.recentWith(recentChannels(prefs), guideId).joinToString(SEPARATOR.toString())
        }
    }

    override suspend fun setGuideFilter(key: String?) {
        dataStore.edit { prefs -> if (key == null) prefs.remove(GUIDE_FILTER) else prefs[GUIDE_FILTER] = key }
    }

    override suspend fun setCaptionsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[CAPTIONS_ENABLED] = enabled }
    }

    override suspend fun setCaptionAdvanceMs(ms: Int) {
        dataStore.edit { prefs -> prefs[CAPTION_ADVANCE_MS] = ms.coerceIn(AppSettings.CAPTION_ADVANCE_RANGE_MS) }
    }

    override suspend fun setChannelCaptionAdvance(guideId: String, ms: Int) {
        dataStore.edit { prefs ->
            val updated = channelAdvances(prefs) + (guideId to ms.coerceIn(AppSettings.CAPTION_ADVANCE_RANGE_MS))
            prefs[CHANNEL_CAPTION_ADVANCE_MS] = updated.mapTo(HashSet()) { (id, value) -> "$id$SEPARATOR$value" }
        }
    }

    override suspend fun setSmoothCaptions(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SMOOTH_CAPTIONS] = enabled }
    }

    override suspend fun setCaptionStyle(style: CaptionStyle) {
        dataStore.edit { prefs -> prefs[CAPTION_STYLE] = style.name }
    }

    override suspend fun setAutoOpenDelay(delay: AutoOpenDelay) {
        dataStore.edit { prefs -> prefs[AUTO_OPEN_DELAY] = delay.name }
    }

    override suspend fun setPreviewSound(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PREVIEW_SOUND] = enabled }
    }

    override suspend fun setVolume(level: Int, muted: Boolean) {
        dataStore.edit { prefs ->
            prefs[VOLUME] = level.coerceIn(0, AppSettings.MAX_VOLUME)
            prefs[VOLUME_MUTED] = muted
        }
    }

    override suspend fun setKeyLegendShown(count: Int) {
        dataStore.edit { prefs -> prefs[KEY_LEGEND_SHOWN] = count }
    }

    override suspend fun setStartup(target: StartupTarget, guideId: String?) {
        dataStore.edit { prefs ->
            prefs[STARTUP] = target.name
            val channel = guideId.takeIf { target == StartupTarget.CHANNEL }
            if (channel == null) prefs.remove(STARTUP_CHANNEL) else prefs[STARTUP_CHANNEL] = channel
        }
    }

    override suspend fun setLastTab(tab: TopLevelTab) {
        dataStore.edit { prefs -> prefs[LAST_TAB] = tab.name }
    }

    override suspend fun setClockFormat(format: ClockFormat) {
        dataStore.edit { prefs -> prefs[CLOCK_FORMAT] = format.name }
    }

    override suspend fun setGuideDensity(density: GuideDensity) {
        dataStore.edit { prefs -> prefs[GUIDE_DENSITY] = density.name }
    }

    override suspend fun setChannelHidden(guideId: String, hidden: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[HIDDEN_CHANNELS] ?: emptySet()
            prefs[HIDDEN_CHANNELS] = if (hidden) current + guideId else current - guideId
        }
    }

    override suspend fun setChannelsHidden(guideIds: Collection<String>, hidden: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[HIDDEN_CHANNELS] ?: emptySet()
            prefs[HIDDEN_CHANNELS] = if (hidden) current + guideIds else current - guideIds.toSet()
        }
    }

    override suspend fun setZapSkipsHidden(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[ZAP_SKIPS_HIDDEN] = enabled }
    }

    override suspend fun setSkipCommercials(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SKIP_COMMERCIALS] = enabled }
    }

    override suspend fun setLiveBreaks(mode: LiveBreaks) {
        dataStore.edit { prefs -> prefs[LIVE_BREAKS] = mode.name }
    }

    override suspend fun addAdsDodged(day: String, ms: Long) {
        dataStore.edit { prefs ->
            val dodged = AdsDodged(prefs[ADS_DODGED_DAY].orEmpty(), prefs[ADS_DODGED_MS] ?: 0L).plus(day, ms)
            prefs[ADS_DODGED_DAY] = dodged.day
            prefs[ADS_DODGED_MS] = dodged.ms
        }
    }

    override suspend fun setTrackChoice(showId: String, choice: TrackChoice) {
        dataStore.edit { prefs ->
            val kept = (listOf(showId to choice) + trackChoices(prefs).filterKeys { it != showId }.toList()).take(TrackChoice.KEPT)
            prefs[TRACK_CHOICES] = kept.joinToString("\n") { (id, c) -> "$id$SEPARATOR${c.encode()}" }
        }
    }

    private fun trackChoices(prefs: Preferences): Map<String, TrackChoice> =
        prefs[TRACK_CHOICES].orEmpty().lines().mapNotNull { line ->
            val id = line.substringBefore(SEPARATOR, "").ifEmpty { return@mapNotNull null }
            TrackChoice.decode(line.substringAfter(SEPARATOR))?.let { id to it }
        }.toMap()

    override suspend fun setFavoritesOrder(order: List<String>) {
        dataStore.edit { prefs -> prefs[FAVORITES_ORDER] = order.distinct().joinToString(SEPARATOR.toString()) }
    }

    override suspend fun setMatchFrameRate(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[MATCH_FRAME_RATE] = enabled }
    }

    override suspend fun setDecoder(decoder: DecoderPreference) {
        dataStore.edit { prefs -> prefs[DECODER] = decoder.name }
    }

    override suspend fun setChannelDecoder(guideId: String, decoder: DecoderPreference?) {
        dataStore.edit { prefs ->
            val updated = if (decoder == null) channelDecoders(prefs) - guideId else channelDecoders(prefs) + (guideId to decoder)
            prefs[CHANNEL_DECODER] = updated.mapTo(HashSet()) { (id, value) -> "$id$SEPARATOR${value.name}" }
        }
    }

    override suspend fun setBuffer(buffer: BufferPreference) {
        dataStore.edit { prefs -> prefs[BUFFER] = buffer.name }
    }

    override suspend fun ensureClientId(): String {
        val stored = dataStore.data.first()[CLIENT_ID]
        if (!stored.isNullOrEmpty()) return stored
        return dataStore.edit { prefs -> if (prefs[CLIENT_ID].isNullOrEmpty()) prefs[CLIENT_ID] = Uuid.random().toString() }[CLIENT_ID]!!
    }

    override suspend fun applyServerPreferences(hidden: Set<String>, favorites: List<String>, recent: List<String>, syncedAt: String) {
        dataStore.edit { prefs ->
            prefs[HIDDEN_CHANNELS] = hidden
            prefs[FAVORITES] = favorites.toSet()
            prefs[FAVORITES_ORDER] = favorites.distinct().joinToString(SEPARATOR.toString())
            val merged = recent.distinct().take(AppSettings.RECENT_CHANNELS)
            prefs[RECENT_CHANNELS] = merged.joinToString(SEPARATOR.toString())
            if (prefs[LAST_CHANNEL] == null) merged.firstOrNull()?.let { prefs[LAST_CHANNEL] = it }
            prefs[PREFERENCES_SYNCED_AT] = syncedAt
        }
    }

    override suspend fun setPreferencesSyncedAt(syncedAt: String) {
        dataStore.edit { prefs -> prefs[PREFERENCES_SYNCED_AT] = syncedAt }
    }

    override suspend fun setSettingsPin(hash: String?) {
        dataStore.edit { prefs -> if (hash.isNullOrEmpty()) prefs.remove(SETTINGS_PIN) else prefs[SETTINGS_PIN] = hash }
    }

    private fun favoritesOrder(prefs: Preferences): List<String> =
        prefs[FAVORITES_ORDER].orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }

    /** Entries are `guideId<TAB>DECODER`; anything malformed or unknown is skipped. */
    private fun channelDecoders(prefs: Preferences): Map<String, DecoderPreference> = prefs[CHANNEL_DECODER].orEmpty().mapNotNull { entry ->
        val id = entry.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")
        val value = DecoderPreference.entries.firstOrNull { it.name == entry.substringAfterLast(SEPARATOR) }
        if (id.isEmpty() || value == null) null else id to value
    }.toMap()

    /** A stored enum name, or [default] when the value is missing or from a build that no longer has it. */
    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T = enumEntries<T>().firstOrNull { it.name == this } ?: default

    private fun advanceMs(prefs: Preferences): Int =
        (prefs[CAPTION_ADVANCE_MS] ?: AppSettings.DEFAULT_CAPTION_ADVANCE_MS).coerceIn(AppSettings.CAPTION_ADVANCE_RANGE_MS)

    /** Newest first, joined by tabs, since a preferences string set has no order. */
    private fun recentChannels(prefs: Preferences): List<String> =
        prefs[RECENT_CHANNELS].orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }.distinct().take(AppSettings.RECENT_CHANNELS)

    /** Entries are `guideId<TAB>ms`; anything malformed is skipped. */
    private fun channelAdvances(prefs: Preferences): Map<String, Int> = prefs[CHANNEL_CAPTION_ADVANCE_MS].orEmpty().mapNotNull { entry ->
        val id = entry.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")
        val value = entry.substringAfterLast(SEPARATOR).toIntOrNull()
        if (id.isEmpty() || value == null) null else id to value.coerceIn(AppSettings.CAPTION_ADVANCE_RANGE_MS)
    }.toMap()

    private companion object {
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
        val CLOCK_FORMAT = stringPreferencesKey("clock_format")
        val GUIDE_DENSITY = stringPreferencesKey("guide_density")
        val HIDDEN_CHANNELS = stringSetPreferencesKey("hidden_channels")
        val ZAP_SKIPS_HIDDEN = booleanPreferencesKey("zap_skips_hidden")
        val SKIP_COMMERCIALS = booleanPreferencesKey("skip_commercials")
        val LIVE_BREAKS = stringPreferencesKey("live_breaks")
        val ADS_DODGED_DAY = stringPreferencesKey("ads_dodged_day")
        val ADS_DODGED_MS = longPreferencesKey("ads_dodged_ms")
        val FAVORITES_ORDER = stringPreferencesKey("favorites_order")
        val TRACK_CHOICES = stringPreferencesKey("track_choices")
        val MATCH_FRAME_RATE = booleanPreferencesKey("match_frame_rate")
        val DECODER = stringPreferencesKey("decoder")
        val CHANNEL_DECODER = stringSetPreferencesKey("channel_decoder")
        val BUFFER = stringPreferencesKey("buffer")
        val CLIENT_ID = stringPreferencesKey("client_id")
        val PREFERENCES_SYNCED_AT = stringPreferencesKey("preferences_synced_at")
        val SETTINGS_PIN = stringPreferencesKey("settings_pin")
        const val SEPARATOR = '\t'
    }
}

/** A preferences store in [file]; a corrupt file is replaced with defaults. Create one per file, as a DI single. */
fun preferencesDataStore(file: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
    ) { file.toPath() }
