package dev.jamesrich.sofaking.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val defaults: AppSettings,
) : SettingsStore {

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[SERVER_URL] ?: defaults.serverUrl,
            profile = prefs[PROFILE] ?: defaults.profile,
            favorites = prefs[FAVORITES] ?: emptySet(),
            lastChannel = prefs[LAST_CHANNEL],
            captionsEnabled = prefs[CAPTIONS_ENABLED] ?: false,
            captionAdvanceMs = advanceMs(prefs),
            captionAdvanceByChannel = channelAdvances(prefs),
            smoothCaptions = prefs[SMOOTH_CAPTIONS] ?: true,
            captionStyle = prefs[CAPTION_STYLE]?.let { name ->
                CaptionStyle.entries.firstOrNull { it.name == name }
            } ?: CaptionStyle.SYSTEM,
        )
    }

    override suspend fun setServer(serverUrl: String, profile: String) {
        val server = serverUrl.trim().trimEnd('/')
        val channelProfile = profile.trim()
        dataStore.edit { prefs ->
            prefs[SERVER_URL] = server
            prefs[PROFILE] = channelProfile
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
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val PROFILE = stringPreferencesKey("profile")
        private val FAVORITES = stringSetPreferencesKey("favorites")
        private val LAST_CHANNEL = stringPreferencesKey("last_channel")
        private val CAPTIONS_ENABLED = booleanPreferencesKey("captions_enabled")
        private val CAPTION_ADVANCE_MS = intPreferencesKey("caption_advance_ms")
        private val CHANNEL_CAPTION_ADVANCE_MS = stringSetPreferencesKey("channel_caption_advance_ms")
        private val SMOOTH_CAPTIONS = booleanPreferencesKey("smooth_captions")
        private val CAPTION_STYLE = stringPreferencesKey("caption_style")
        private val LEGACY_CHANNEL_CAPTION_ADVANCE = stringSetPreferencesKey("channel_caption_advance")
        private const val SEPARATOR = '\t'

        private val lock = Any()
        private var sharedDataStore: DataStore<Preferences>? = null

        /** One DataStore per process: creating two for the same file throws. */
        fun create(context: Context, defaults: AppSettings): DataStoreSettingsStore {
            val appContext = context.applicationContext
            val store = synchronized(lock) {
                sharedDataStore ?: PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                ) { appContext.preferencesDataStoreFile("settings") }.also { sharedDataStore = it }
            }
            return DataStoreSettingsStore(store, defaults)
        }
    }
}
