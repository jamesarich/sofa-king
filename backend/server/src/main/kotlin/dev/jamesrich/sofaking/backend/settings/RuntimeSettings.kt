package dev.jamesrich.sofaking.backend.settings

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.logging.LogSettings
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.runtimeSettings
import dev.jamesrich.sofaking.backend.store.saveRuntimeSettings
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The settings the server changes without a restart: the log level, how far ahead the XMLTV reaches, the Plex sync
 * interval, the streams an HDHomeRun device may open and the sessions the server may hold. [plexSyncMinutes] is kept
 * whether or not Plex sync is configured, so it is there when it is.
 */
data class RuntimeValues(val logLevel: String, val guideDays: Int, val plexSyncMinutes: Int, val hdhrTuners: Int, val maxSessions: Int) {
    /** The values by their variable name, as the settings page and the database name them. */
    fun byName(): Map<String, String> = mapOf(
        LOG_LEVEL to logLevel, GUIDE_DAYS to guideDays.toString(), PLEX_SYNC_MINUTES to plexSyncMinutes.toString(),
        HDHR_TUNERS to hdhrTuners.toString(), MAX_SESSIONS to maxSessions.toString(),
    )

    companion object {
        const val LOG_LEVEL = "SOFA_LOG_LEVEL"
        const val GUIDE_DAYS = "SOFA_XMLTV_DAYS"
        const val PLEX_SYNC_MINUTES = "SOFA_PLEX_SYNC_MINUTES"
        const val HDHR_TUNERS = "SOFA_HDHR_TUNERS"
        const val MAX_SESSIONS = "SOFA_MAX_SESSIONS"
        val NAMES = listOf(LOG_LEVEL, GUIDE_DAYS, PLEX_SYNC_MINUTES, HDHR_TUNERS, MAX_SESSIONS)
        /** The server's defaults, for a runtime built without a [ServerConfig]. */
        val DEFAULT = RuntimeValues("info", 3, 60, 2, 4)

        /** What the environment set, the values the server starts with until a saved setting replaces one. */
        fun of(config: ServerConfig) = RuntimeValues(config.logging.level, config.outputs.guideDays, config.plex?.syncMinutes ?: 60, config.hdhr.tuners, config.maxSessions)

        /**
         * [environment] with the saved [overrides] applied, by name; a value that does not parse or is out of range is
         * refused with a message naming the setting.
         */
        fun from(environment: RuntimeValues, overrides: Map<String, String>): RuntimeValues {
            fun int(name: String, current: Int, range: IntRange): Int {
                val raw = overrides[name] ?: return current
                return raw.trim().toIntOrNull()?.takeIf { it in range } ?: throw ApiFailure(400, "INVALID_REQUEST", "$name must be between ${range.first} and ${range.last}")
            }
            val level = overrides[LOG_LEVEL]?.trim()?.lowercase() ?: environment.logLevel
            if (LOG_LEVEL in overrides && level !in LogSettings.LEVELS) throw ApiFailure(400, "INVALID_REQUEST", "$LOG_LEVEL must be one of ${LogSettings.LEVELS.joinToString()}")
            val sessions = int(MAX_SESSIONS, environment.maxSessions, 1..16)
            val tuners = int(HDHR_TUNERS, environment.hdhrTuners, 1..16)
            // The environment's own pair is the server's to live with (startup validated it); only a saved value is held to the rule.
            if ((HDHR_TUNERS in overrides || MAX_SESSIONS in overrides) && tuners > sessions) throw ApiFailure(400, "INVALID_REQUEST", "$HDHR_TUNERS must be between 1 and $MAX_SESSIONS ($sessions)")
            return RuntimeValues(level, int(GUIDE_DAYS, environment.guideDays, 1..14), int(PLEX_SYNC_MINUTES, environment.plexSyncMinutes, 5..1440), tuners, sessions)
        }
    }
}

/**
 * The runtime settings as the server uses them: the environment's values, with the ones the operator saved in the
 * database over them. [current] is what every consumer reads; a change notifies the listeners that must act on it,
 * such as the session cap and the Plex sync loop.
 */
class RuntimeSettings(private val store: BackendStore, val environment: RuntimeValues, private val clock: Clock) {
    private val state = AtomicReference(environment)
    private val listeners = CopyOnWriteArrayList<(RuntimeValues) -> Unit>()
    private val mutex = Mutex()

    val current: RuntimeValues get() = state.get()

    /** The names the operator saved, which take their value from the database instead of the environment. */
    @Volatile var saved: Set<String> = emptySet()
        private set

    /** Runs [listener] on every change, and once now, so a consumer starts on the saved values. */
    fun onChange(listener: (RuntimeValues) -> Unit) {
        listeners += listener
        listener(current)
    }

    /** Reads what the operator saved; a saved value that no longer fits is dropped and logged by the caller. */
    suspend fun load(): List<String> = mutex.withLock {
        val overrides = store.runtimeSettings()
        val problems = mutableListOf<String>()
        val kept = overrides.filter { (name, value) ->
            try { RuntimeValues.from(environment, mapOf(name to value)); true } catch (e: ApiFailure) { problems += e.message; false }
        }
        apply(RuntimeValues.from(environment, kept), kept.keys)
        problems
    }

    /**
     * Saves [overrides], by name, and applies the result; a name that is absent goes back to the environment's value.
     * Refuses the whole set with [ApiFailure] when any value is invalid, and saves nothing then.
     */
    suspend fun update(overrides: Map<String, String>): RuntimeValues = mutex.withLock {
        val next = RuntimeValues.from(environment, overrides)
        // A saved value equal to the environment's is dropped, so an environment change later shows through.
        val kept = next.byName().filter { (name, value) -> name in overrides && environment.byName()[name] != value }
        store.saveRuntimeSettings(kept, clock.instant())
        apply(next, kept.keys)
        next
    }

    private fun apply(values: RuntimeValues, savedNames: Set<String>) {
        val previous = state.getAndSet(values)
        saved = savedNames
        if (previous != values) listeners.forEach { it(values) }
    }
}
