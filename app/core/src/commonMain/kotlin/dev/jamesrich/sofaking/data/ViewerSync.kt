package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The part of [AppSettings] the server keeps per client: what [ViewerSync] compares and sends. */
internal data class SyncedPreferences(val hidden: Set<String>, val favorites: List<String>, val recent: List<String>) {
    constructor(
        settings: AppSettings,
    ) : this(settings.hiddenChannels, orderedFavoriteIds(settings), settings.recentChannels)

    fun toDto(client: ClientInfo) =
        ViewerPreferencesDto(hidden.sorted(), favorites, recent, client.deviceName, client.platform)

    companion object {
        fun of(dto: ViewerPreferencesDto) =
            SyncedPreferences(dto.hidden.toSet(), dto.favorites.distinct(), dto.recent.distinct())

        /**
         * The starred ids in the viewer's order, then the rest as stored, so the server's list carries the order too.
         */
        private fun orderedFavoriteIds(settings: AppSettings): List<String> =
            settings.favoritesOrder.filter { it in settings.favorites } +
                settings.favorites.filterNot { it in settings.favoritesOrder }.sorted()
    }
}

/**
 * Keeps this device's hidden channels, favorites and recent list in step with the server's copy under the client id.
 * When a server comes up, the server's document is taken over if it changed since this device last synced (a
 * reinstall or a second device wrote it); otherwise, and after every local change, this device's copy is pushed.
 * A server without the preferences routes answers 404 to the push and is left alone until the server changes.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ViewerSync(
    private val store: SettingsStore,
    private val client: ClientInfo,
    private val settleTime: Duration = 3.seconds,
    private val retryAfter: Duration = 1.minutes,
) {
    /** Follows [servers] for as long as the caller collects: each new server restarts the sync against it. */
    suspend fun run(servers: Flow<SofaKingApi?>) {
        servers.collectLatest { api -> if (api != null) sync(api) }
    }

    private suspend fun sync(api: SofaKingApi) {
        val clientId = store.ensureClientId()
        // Until the server answers, this device cannot tell a missing copy from a newer one, so it pushes nothing.
        val copy = serverCopy(api, clientId) ?: return
        var known = copy.preferences?.let { adopt(it) }
        var supported = true
        // Every change from here on, the current state first; the one just taken over is skipped by the comparison.
        store.settings.map(::SyncedPreferences).distinctUntilChanged().debounce(settleTime).collectLatest { local ->
            if (!supported || local == known) return@collectLatest
            if (push(api, clientId, local)) known = local else supported = false
        }
    }

    /** The server's copy, asked for until the server answers; null when the server refuses to read it. */
    private suspend fun serverCopy(api: SofaKingApi, clientId: String): ServerCopy? {
        while (true) {
            try {
                return ServerCopy(api.preferences(clientId))
            } catch (e: SofaKingException) {
                // A refusal will not change by asking again; a timeout, a rate limit or a server error may.
                if (e.refusal) {
                    log.i { "preferences: the server refused to read them (${e.code}); not syncing" }
                    return null
                }
                log.w { "preferences: could not read the server's copy (${e.code})" }
            } catch (e: IOException) {
                log.w { "preferences: could not read the server's copy (${e.message})" }
            }
            delay(retryAfter)
        }
    }

    /** [remote] as this device has it now: taken over when it changed since this device last synced. */
    private suspend fun adopt(remote: ViewerPreferencesDto): SyncedPreferences {
        val taken = SyncedPreferences.of(remote)
        if (remote.updatedAt.isNotEmpty() && remote.updatedAt != store.settings.first().preferencesSyncedAt) {
            log.i { "preferences: taking the server's copy from ${remote.updatedAt}" }
            store.applyServerPreferences(taken.hidden, taken.favorites, taken.recent, remote.updatedAt)
        }
        return taken
    }

    /** Pushes [local] until the server keeps it: true once saved, false when the server does not keep preferences. */
    private suspend fun push(api: SofaKingApi, clientId: String, local: SyncedPreferences): Boolean {
        while (true) {
            try {
                val saved = api.savePreferences(clientId, local.toDto(client))
                store.setPreferencesSyncedAt(saved.updatedAt)
                return true
            } catch (e: SofaKingException) {
                if (e.statusCode == HttpStatusCode.NotFound || e.statusCode == HttpStatusCode.Unauthorized) {
                    log.i { "preferences: the server does not keep them (${e.code}); not syncing" }
                    return false
                }
                log.w { "preferences: push refused (${e.code})" }
            } catch (e: IOException) {
                log.w { "preferences: push failed (${e.message})" }
            }
            delay(retryAfter)
        }
    }

    /** The server's answer when asked for its copy: null [preferences] when it has none. */
    private class ServerCopy(val preferences: ViewerPreferencesDto?)

    private companion object {
        val log = Logger.withTag("SofaKing")
    }
}

/** A client error asking again will not change: any 4xx but a timeout or a rate limit. */
private val SofaKingException.refusal: Boolean
    get() = status in CLIENT_ERRORS && statusCode != HttpStatusCode.RequestTimeout &&
        statusCode != HttpStatusCode.TooManyRequests

private val CLIENT_ERRORS = 400..499
