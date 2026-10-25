package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The part of [AppSettings] the household shares: what [HouseholdSync] compares and sends. */
internal data class SharedPreferences(val hidden: Set<String>, val favorites: List<String>) {
    fun toDto() = HouseholdPreferencesDto(favorites = favorites, hidden = hidden.sorted())

    companion object {
        fun of(settings: AppSettings) = SyncedPreferences(settings).let { SharedPreferences(it.hidden, it.favorites) }

        fun of(dto: HouseholdPreferencesDto) = SharedPreferences(dto.hidden.toSet(), dto.favorites.distinct())
    }
}

/**
 * Keeps this device's favorites and hidden channels in step with the household's shared copy while
 * [AppSettings.shareFavorites] is on and the server keeps one, as [ViewerSync] does with the device's own copy: the
 * household's copy is taken over when another device changed it since this one last synced, and every local change
 * is pushed. The device's own copy, recent channels and all, keeps syncing beside it.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HouseholdSync(
    private val store: SettingsStore,
    private val settleTime: Duration = 3.seconds,
    private val retryAfter: Duration = 1.minutes,
) {
    /** Follows [servers] and whether each [offers] the shared copy, for as long as the caller collects. */
    suspend fun run(servers: Flow<SofaKingApi?>, offers: Flow<Boolean>) {
        val sharing = store.settings.map { it.shareFavorites }.distinctUntilChanged()
        combine(servers, offers.distinctUntilChanged(), sharing) { api, offered, share ->
            api.takeIf { offered && share }
        }
            .collectLatest { api -> if (api != null) sync(api) }
    }

    private suspend fun sync(api: SofaKingApi) {
        val remote = serverCopy(api)
        var known = remote.takeIf { it.updatedAt.isNotEmpty() }?.let { adopt(it) }
        store.settings.map(SharedPreferences::of).distinctUntilChanged().debounce(settleTime).collectLatest { local ->
            if (local != known) {
                push(api, local)
                known = local
            }
        }
    }

    /** The household's copy, asked for until the server answers. */
    private suspend fun serverCopy(api: SofaKingApi): HouseholdPreferencesDto {
        while (true) {
            try {
                return api.householdPreferences()
            } catch (e: SofaKingException) {
                log.w { "household preferences: could not read the shared copy (${e.code})" }
            } catch (e: IOException) {
                log.w { "household preferences: could not read the shared copy (${e.message})" }
            }
            delay(retryAfter)
        }
    }

    /** [remote] as this device has it now: taken over when it changed since this device last synced. */
    private suspend fun adopt(remote: HouseholdPreferencesDto): SharedPreferences {
        val taken = SharedPreferences.of(remote)
        if (remote.updatedAt != store.settings.first().householdSyncedAt) {
            log.i { "household preferences: taking the shared copy from ${remote.updatedAt}" }
            store.applyHouseholdPreferences(taken.hidden, taken.favorites, remote.updatedAt)
        }
        return taken
    }

    /** Pushes [local] until the server keeps it. */
    private suspend fun push(api: SofaKingApi, local: SharedPreferences) {
        while (true) {
            try {
                store.setHouseholdSyncedAt(api.saveHouseholdPreferences(local.toDto()).updatedAt)
                return
            } catch (e: SofaKingException) {
                log.w { "household preferences: push refused (${e.code})" }
            } catch (e: IOException) {
                log.w { "household preferences: push failed (${e.message})" }
            }
            delay(retryAfter)
        }
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
    }
}
