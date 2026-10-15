package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/** [SettingsStore] on a preferences [DataStore]; there must be only one per file, which the DI graph's single instance guarantees. */
class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    // An unreadable store reads as defaults rather than failing the flow, which would take the whole app down.
    override val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is CancellationException) throw it else emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                serverUrl = prefs[SERVER_URL].orEmpty(),
                apiToken = prefs[API_TOKEN].orEmpty(),
                favorites = prefs[FAVORITES] ?: emptySet(),
                lastChannel = prefs[LAST_CHANNEL],
                recentChannels = recentChannels(prefs),
                captionsEnabled = prefs[CAPTIONS_ENABLED] ?: false,
                captionAdvanceMs = advanceMs(prefs),
                captionAdvanceByChannel = channelAdvances(prefs),
                smoothCaptions = prefs[SMOOTH_CAPTIONS] ?: true,
                captionStyle = prefs[CAPTION_STYLE]?.let { name ->
                    CaptionStyle.entries.firstOrNull { it.name == name }
                } ?: CaptionStyle.SYSTEM,
                autoOpenDelay = prefs[AUTO_OPEN_DELAY]?.let { name ->
                    AutoOpenDelay.entries.firstOrNull { it.name == name }
                } ?: AutoOpenDelay.AFTER_10_SECONDS,
                previewSound = prefs[PREVIEW_SOUND] ?: true,
                keyLegendShown = prefs[KEY_LEGEND_SHOWN] ?: 0,
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
        }
    }

    override suspend fun setLastChannel(guideId: String) {
        dataStore.edit { prefs ->
            prefs[LAST_CHANNEL] = guideId
            prefs[RECENT_CHANNELS] = AppSettings.recentWith(recentChannels(prefs), guideId).joinToString(SEPARATOR.toString())
        }
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

    override suspend fun setKeyLegendShown(count: Int) {
        dataStore.edit { prefs -> prefs[KEY_LEGEND_SHOWN] = count }
    }

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
        val CAPTIONS_ENABLED = booleanPreferencesKey("captions_enabled")
        val CAPTION_ADVANCE_MS = intPreferencesKey("caption_advance_ms")
        val CHANNEL_CAPTION_ADVANCE_MS = stringSetPreferencesKey("channel_caption_advance_ms")
        val SMOOTH_CAPTIONS = booleanPreferencesKey("smooth_captions")
        val CAPTION_STYLE = stringPreferencesKey("caption_style")
        val AUTO_OPEN_DELAY = stringPreferencesKey("auto_open_delay")
        val PREVIEW_SOUND = booleanPreferencesKey("preview_sound")
        val KEY_LEGEND_SHOWN = intPreferencesKey("key_legend_shown")
        const val SEPARATOR = '\t'
    }
}

/** A preferences store in [file]; a corrupt file is replaced with defaults. Create one per file, as a DI single. */
fun preferencesDataStore(file: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
    ) { file.toPath() }
