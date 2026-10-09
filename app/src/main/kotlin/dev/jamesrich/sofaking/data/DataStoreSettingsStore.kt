package dev.jamesrich.sofaking.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val PROFILE = stringPreferencesKey("profile")
        private val FAVORITES = stringSetPreferencesKey("favorites")
        private val LAST_CHANNEL = stringPreferencesKey("last_channel")
        private val CAPTIONS_ENABLED = booleanPreferencesKey("captions_enabled")

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
