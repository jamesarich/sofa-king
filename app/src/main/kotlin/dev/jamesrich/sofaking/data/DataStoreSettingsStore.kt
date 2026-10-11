package dev.jamesrich.sofaking.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    // An unreadable file reads as defaults rather than failing the flow, which would take the whole app down.
    override val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
        AppSettings(
            serverUrl = prefs[SERVER_URL].orEmpty(),
            apiToken = prefs[API_TOKEN].orEmpty(),
            favorites = prefs[FAVORITES] ?: emptySet(),
            lastChannel = prefs[LAST_CHANNEL],
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
        )
    }

    override suspend fun setServer(serverUrl: String, apiToken: String) {
        val server = ServerAddress.normalizeUrl(serverUrl) ?: serverUrl.trim().trimEnd('/')
        val token = ServerAddress.normalizeToken(apiToken) ?: apiToken.trim()
        dataStore.edit { prefs ->
            prefs[SERVER_URL] = server
            prefs[API_TOKEN] = token
            // Left by installs that pointed at a playlist server; never read, and freed here.
            prefs.remove(LEGACY_SERVER_URL)
            prefs.remove(LEGACY_PROFILE)
        }
    }

    override suspend fun toggleFavorite(guideId: String) {
        dataStore.edit { prefs ->
            val current = prefs[FAVORITES] ?: emptySet()
            prefs[FAVORITES] = if (guideId in current) current - guideId else current + guideId
        }
    }

    override suspend fun setLastChannel(guideId: String) {
        dataStore.edit { prefs -> prefs[LAST_CHANNEL] = guideId }
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

    /** The old whole-seconds default is deliberately not migrated: the default is now 0 and per-channel values carry the tuning. */
    private fun advanceMs(prefs: Preferences): Int =
        (prefs[CAPTION_ADVANCE_MS] ?: AppSettings.DEFAULT_CAPTION_ADVANCE_MS).coerceIn(AppSettings.CAPTION_ADVANCE_RANGE_MS)

    /** Per-channel values follow the same rule as [advanceMs]: the ms set wins once it exists. */
    private fun channelAdvances(prefs: Preferences): Map<String, Int> =
        prefs[CHANNEL_CAPTION_ADVANCE_MS]?.let { decodeAdvances(it, legacy = false) }
            ?: decodeAdvances(prefs[LEGACY_CHANNEL_CAPTION_ADVANCE].orEmpty(), legacy = true)

    /** Entries are `guideId<TAB>value`; anything malformed is skipped. */
    private fun decodeAdvances(entries: Set<String>, legacy: Boolean): Map<String, Int> = entries.mapNotNull { entry ->
        val id = entry.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")
        val value = entry.substringAfterLast(SEPARATOR).toIntOrNull()
        when {
            id.isEmpty() || value == null -> null
            legacy -> id to legacySecondsToMs(value)
            else -> id to value.coerceIn(AppSettings.CAPTION_ADVANCE_RANGE_MS)
        }
    }.toMap()

    /** The old setting was clamped to 0..6 whole seconds; keep that bound before scaling. */
    private fun legacySecondsToMs(seconds: Int): Int = seconds.coerceIn(0..6) * 1_000

    companion object {
        private val SERVER_URL = stringPreferencesKey("backend_url")
        private val API_TOKEN = stringPreferencesKey("api_token")
        private val FAVORITES = stringSetPreferencesKey("favorites")
        private val LAST_CHANNEL = stringPreferencesKey("last_channel")
        private val CAPTIONS_ENABLED = booleanPreferencesKey("captions_enabled")
        private val CAPTION_ADVANCE_MS = intPreferencesKey("caption_advance_ms")
        private val CHANNEL_CAPTION_ADVANCE_MS = stringSetPreferencesKey("channel_caption_advance_ms")
        private val SMOOTH_CAPTIONS = booleanPreferencesKey("smooth_captions")
        private val CAPTION_STYLE = stringPreferencesKey("caption_style")
        private val AUTO_OPEN_DELAY = stringPreferencesKey("auto_open_delay")
        private val PREVIEW_SOUND = booleanPreferencesKey("preview_sound")
        private val LEGACY_CHANNEL_CAPTION_ADVANCE = stringSetPreferencesKey("channel_caption_advance")
        private val LEGACY_SERVER_URL = stringPreferencesKey("server_url")
        private val LEGACY_PROFILE = stringPreferencesKey("profile")
        private const val SEPARATOR = '\t'

        private val lock = Any()
        private var sharedDataStore: DataStore<Preferences>? = null

        /** One DataStore per process: creating two for the same file throws. A corrupt file is replaced with defaults. */
        fun create(context: Context): DataStoreSettingsStore {
            val appContext = context.applicationContext
            val store = synchronized(lock) {
                sharedDataStore ?: PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                ) { appContext.preferencesDataStoreFile("settings") }.also { sharedDataStore = it }
            }
            return DataStoreSettingsStore(store)
        }
    }
}
