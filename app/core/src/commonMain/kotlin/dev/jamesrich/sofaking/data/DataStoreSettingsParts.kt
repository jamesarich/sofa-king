package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.jamesrich.sofaking.data.SettingsKeys.ADS_DODGED_DAY
import dev.jamesrich.sofaking.data.SettingsKeys.ADS_DODGED_MS
import dev.jamesrich.sofaking.data.SettingsKeys.API_TOKEN
import dev.jamesrich.sofaking.data.SettingsKeys.AUTO_OPEN_DELAY
import dev.jamesrich.sofaking.data.SettingsKeys.AUTO_SKIP_INTROS
import dev.jamesrich.sofaking.data.SettingsKeys.BUFFER
import dev.jamesrich.sofaking.data.SettingsKeys.CAPTIONS_ENABLED
import dev.jamesrich.sofaking.data.SettingsKeys.CAPTION_ADVANCE_MS
import dev.jamesrich.sofaking.data.SettingsKeys.CAPTION_STYLE
import dev.jamesrich.sofaking.data.SettingsKeys.CHANNEL_CAPTION_ADVANCE_MS
import dev.jamesrich.sofaking.data.SettingsKeys.CHANNEL_DECODER
import dev.jamesrich.sofaking.data.SettingsKeys.CLIENT_ID
import dev.jamesrich.sofaking.data.SettingsKeys.CLOCK_FORMAT
import dev.jamesrich.sofaking.data.SettingsKeys.DECODER
import dev.jamesrich.sofaking.data.SettingsKeys.FAVORITES
import dev.jamesrich.sofaking.data.SettingsKeys.FAVORITES_ORDER
import dev.jamesrich.sofaking.data.SettingsKeys.GUIDE_DENSITY
import dev.jamesrich.sofaking.data.SettingsKeys.GUIDE_FILTER
import dev.jamesrich.sofaking.data.SettingsKeys.HIDDEN_CHANNELS
import dev.jamesrich.sofaking.data.SettingsKeys.HIDE_SPOILERS
import dev.jamesrich.sofaking.data.SettingsKeys.KEY_LEGEND_SHOWN
import dev.jamesrich.sofaking.data.SettingsKeys.KIDS_MODE
import dev.jamesrich.sofaking.data.SettingsKeys.LAST_CHANNEL
import dev.jamesrich.sofaking.data.SettingsKeys.LAST_TAB
import dev.jamesrich.sofaking.data.SettingsKeys.LIVE_BREAKS
import dev.jamesrich.sofaking.data.SettingsKeys.MATCH_FRAME_RATE
import dev.jamesrich.sofaking.data.SettingsKeys.PREFERENCES_SYNCED_AT
import dev.jamesrich.sofaking.data.SettingsKeys.PREVIEW_SOUND
import dev.jamesrich.sofaking.data.SettingsKeys.RECENT_CHANNELS
import dev.jamesrich.sofaking.data.SettingsKeys.SEEK_BACK_SECONDS
import dev.jamesrich.sofaking.data.SettingsKeys.SEEK_FORWARD_SECONDS
import dev.jamesrich.sofaking.data.SettingsKeys.SEPARATOR
import dev.jamesrich.sofaking.data.SettingsKeys.SERVER_URL
import dev.jamesrich.sofaking.data.SettingsKeys.SETTINGS_PIN
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/** [ChannelSettings] on a preferences [DataStore]. */
internal class DataStoreChannelSettings(private val dataStore: DataStore<Preferences>) : ChannelSettings {
    override suspend fun toggleFavorite(guideId: String) {
        dataStore.edit { prefs ->
            val current = prefs[FAVORITES] ?: emptySet()
            prefs[FAVORITES] = if (guideId in current) current - guideId else current + guideId
            // A channel unstarred loses its place; starred again, it joins the end.
            if (guideId in
                current
            ) {
                prefs[FAVORITES_ORDER] =
                    favoritesOrder(prefs).filterNot { it == guideId }.joinToString(SEPARATOR.toString())
            }
        }
    }

    override suspend fun setLastChannel(guideId: String) {
        dataStore.edit { prefs ->
            prefs[LAST_CHANNEL] = guideId
            prefs[RECENT_CHANNELS] =
                AppSettings.recentWith(recentChannels(prefs), guideId).joinToString(SEPARATOR.toString())
        }
    }

    // Keys are cleared with `-=`: `remove` returns a missing key's null as its non-null type, which throws on Wasm.
    override suspend fun setGuideFilter(key: String?) {
        dataStore.edit { prefs -> if (key == null) prefs -= GUIDE_FILTER else prefs[GUIDE_FILTER] = key }
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

    override suspend fun setFavoritesOrder(order: List<String>) {
        dataStore.edit { prefs -> prefs[FAVORITES_ORDER] = order.distinct().joinToString(SEPARATOR.toString()) }
    }

    override suspend fun setStartup(target: StartupTarget, guideId: String?) {
        dataStore.edit { prefs ->
            prefs[STARTUP] = target.name
            val channel = guideId.takeIf { target == StartupTarget.CHANNEL }
            if (channel == null) prefs -= STARTUP_CHANNEL else prefs[STARTUP_CHANNEL] = channel
        }
    }

    override suspend fun setLastTab(tab: TopLevelTab) {
        dataStore.edit { prefs -> prefs[LAST_TAB] = tab.name }
    }
}

/** [CaptionSettings] on a preferences [DataStore]. */
internal class DataStoreCaptionSettings(private val dataStore: DataStore<Preferences>) : CaptionSettings {
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

    override suspend fun setSubtitleLook(look: SubtitleLook) {
        dataStore.edit { prefs ->
            prefs[SUBTITLE_SIZE] = look.size.name
            prefs[SUBTITLE_RAISE] = look.raise.name
            prefs[SUBTITLE_BOX] = look.box
        }
    }

    override suspend fun setTrackChoice(showId: String, choice: TrackChoice) {
        dataStore.edit { prefs ->
            val kept = (
                listOf(showId to choice) + trackChoices(prefs).filterKeys {
                    it != showId
                }.toList()
                ).take(TrackChoice.KEPT)
            prefs[TRACK_CHOICES] = kept.joinToString("\n") { (id, c) -> "$id$SEPARATOR${c.encode()}" }
        }
    }
}

/** [PlayerSettings] on a preferences [DataStore]. */
internal class DataStorePlayerSettings(private val dataStore: DataStore<Preferences>) : PlayerSettings {
    override suspend fun setVolume(level: Int, muted: Boolean) {
        dataStore.edit { prefs ->
            prefs[VOLUME] = level.coerceIn(0, AppSettings.MAX_VOLUME)
            prefs[VOLUME_MUTED] = muted
        }
    }

    override suspend fun setMatchFrameRate(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[MATCH_FRAME_RATE] = enabled }
    }

    override suspend fun setDecoder(decoder: DecoderPreference) {
        dataStore.edit { prefs -> prefs[DECODER] = decoder.name }
    }

    override suspend fun setChannelDecoder(guideId: String, decoder: DecoderPreference?) {
        dataStore.edit { prefs ->
            val updated = if (decoder == null) {
                channelDecoders(prefs) - guideId
            } else {
                channelDecoders(prefs) + (guideId to decoder)
            }
            prefs[CHANNEL_DECODER] = updated.mapTo(HashSet()) { (id, value) -> "$id$SEPARATOR${value.name}" }
        }
    }

    override suspend fun setBuffer(buffer: BufferPreference) {
        dataStore.edit { prefs -> prefs[BUFFER] = buffer.name }
    }

    override suspend fun setSkip(kind: SkipKind, mode: SkipMode) {
        dataStore.edit { prefs ->
            // An older build on the same device reads its switch, on for automatic.
            when (kind) {
                SkipKind.INTROS -> {
                    prefs[SKIP_INTROS] = mode.name
                    prefs[AUTO_SKIP_INTROS] = mode == SkipMode.AUTO
                }

                SkipKind.CREDITS -> prefs[SKIP_CREDITS] = mode.name

                SkipKind.COMMERCIAL_BREAKS -> {
                    prefs[SKIP_COMMERCIAL_BREAKS] = mode.name
                    prefs[SKIP_COMMERCIALS] = mode == SkipMode.AUTO
                }
            }
        }
    }

    override suspend fun setSeekSteps(steps: SeekSteps) {
        dataStore.edit { prefs ->
            prefs[SEEK_BACK_SECONDS] = steps.backSeconds
            prefs[SEEK_FORWARD_SECONDS] = steps.forwardSeconds
        }
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
}

/** [DisplaySettings] on a preferences [DataStore]. */
internal class DataStoreDisplaySettings(private val dataStore: DataStore<Preferences>) : DisplaySettings {
    override suspend fun setAutoOpenDelay(delay: AutoOpenDelay) {
        dataStore.edit { prefs -> prefs[AUTO_OPEN_DELAY] = delay.name }
    }

    override suspend fun setPreviewSound(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PREVIEW_SOUND] = enabled }
    }

    override suspend fun setKeyLegendShown(count: Int) {
        dataStore.edit { prefs -> prefs[KEY_LEGEND_SHOWN] = count }
    }

    override suspend fun setClockFormat(format: ClockFormat) {
        dataStore.edit { prefs -> prefs[CLOCK_FORMAT] = format.name }
    }

    override suspend fun setGuideDensity(density: GuideDensity) {
        dataStore.edit { prefs -> prefs[GUIDE_DENSITY] = density.name }
    }

    override suspend fun setHideSpoilers(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[HIDE_SPOILERS] = enabled }
    }
}

/** [DeviceSettings] on a preferences [DataStore]. */
internal class DataStoreDeviceSettings(private val dataStore: DataStore<Preferences>) : DeviceSettings {
    override suspend fun setServer(serverUrl: String, apiToken: String) {
        val server = ServerAddress.normalizeUrl(serverUrl) ?: serverUrl.trim().trimEnd('/')
        val token = ServerAddress.normalizeToken(apiToken) ?: apiToken.trim()
        dataStore.edit { prefs ->
            prefs[SERVER_URL] = server
            prefs[API_TOKEN] = token
        }
    }

    override suspend fun ensureClientId(): String {
        val stored = dataStore.data.first()[CLIENT_ID]
        if (!stored.isNullOrEmpty()) return stored
        return dataStore.edit { prefs ->
            if (prefs[CLIENT_ID].isNullOrEmpty()) {
                prefs[CLIENT_ID] =
                    Uuid.random().toString()
            }
        }[CLIENT_ID]!!
    }

    override suspend fun applyServerPreferences(
        hidden: Set<String>,
        favorites: List<String>,
        recent: List<String>,
        syncedAt: String,
    ) {
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
        dataStore.edit { prefs ->
            if (hash.isNullOrEmpty()) {
                prefs -= SETTINGS_PIN
                prefs -= KIDS_MODE
            } else {
                prefs[SETTINGS_PIN] = hash
            }
        }
    }

    override suspend fun setKidsMode(on: Boolean) {
        dataStore.edit { prefs ->
            if (on &&
                prefs[SETTINGS_PIN] != null
            ) {
                prefs[KIDS_MODE] = true
            } else {
                prefs -= KIDS_MODE
            }
        }
    }
}
