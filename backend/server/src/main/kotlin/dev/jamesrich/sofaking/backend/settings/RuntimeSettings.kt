package dev.jamesrich.sofaking.backend.settings

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.logging.LogSettings
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.runtimeSettings
import dev.jamesrich.sofaking.backend.store.saveRuntimeSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The settings the server changes without a restart: the log level, how far ahead the XMLTV reaches, the Plex sync
 * interval, the streams an HDHomeRun device may open, the sessions the server may hold, how the DVR culls recordings:
 * the space it keeps free and the days it keeps a recording (0 keeps them until space runs short), and what live
 * sessions do with the breaks the server learns from the stream itself ([learnedBreaks], see [LearnedBreaks]) and how
 * many channels the ad listener plays at once ([adListeners]).
 * [plexSyncMinutes] is kept whether or not Plex sync is configured, so it is there when it is.
 */
data class RuntimeValues(
    val logLevel: String,
    val guideDays: Int,
    val plexSyncMinutes: Int,
    val hdhrTuners: Int,
    val maxSessions: Int,
    val recordingsFreeGib: Int = 10,
    val recordingsKeepDays: Int = 0,
    val learnedBreaks: String = LearnedBreaks.SHADOW.setting,
    val adListeners: Int = 0,
) {
    /** The values by their variable name, as the settings page and the database name them. */
    fun byName(): Map<String, String> = mapOf(
        LOG_LEVEL to logLevel, GUIDE_DAYS to guideDays.toString(), PLEX_SYNC_MINUTES to plexSyncMinutes.toString(),
        HDHR_TUNERS to hdhrTuners.toString(), MAX_SESSIONS to maxSessions.toString(),
        RECORDINGS_FREE_GIB to recordingsFreeGib.toString(), RECORDINGS_KEEP_DAYS to recordingsKeepDays.toString(),
        LEARNED_BREAKS to learnedBreaks, AD_LISTENERS to adListeners.toString(),
    )

    companion object {
        const val LOG_LEVEL = "SOFA_LOG_LEVEL"
        const val GUIDE_DAYS = "SOFA_XMLTV_DAYS"
        const val PLEX_SYNC_MINUTES = "SOFA_PLEX_SYNC_MINUTES"
        const val HDHR_TUNERS = "SOFA_HDHR_TUNERS"
        const val MAX_SESSIONS = "SOFA_MAX_SESSIONS"
        const val RECORDINGS_FREE_GIB = "SOFA_RECORDINGS_FREE_GIB"
        const val RECORDINGS_KEEP_DAYS = "SOFA_RECORDINGS_KEEP_DAYS"
        const val LEARNED_BREAKS = "SOFA_LEARNED_BREAKS"
        const val AD_LISTENERS = "SOFA_AD_LISTENERS"
        val NAMES =
            listOf(
                LOG_LEVEL, GUIDE_DAYS, PLEX_SYNC_MINUTES, HDHR_TUNERS, MAX_SESSIONS, RECORDINGS_FREE_GIB,
                RECORDINGS_KEEP_DAYS, LEARNED_BREAKS, AD_LISTENERS,
            )
        val AD_LISTENERS_RANGE = 0..24
        val FREE_GIB_RANGE = 0..100_000
        val KEEP_DAYS_RANGE = 0..3650

        /** The server's defaults, for a runtime built without a [ServerConfig]. */
        val DEFAULT = RuntimeValues("info", 3, 60, 2, 4)

        /** What the environment set, the values the server starts with until a saved setting replaces one. */
        fun of(config: ServerConfig) = RuntimeValues(
            config.logging.level, config.outputs.guideDays,
            config.plex?.syncMinutes ?: 60, config.hdhr.tuners, config.maxSessions, config.recordingsFreeGib,
            config.recordingsKeepDays, config.learnedBreaks.setting, config.adListeners,
        )

        /**
         * [environment] with the saved [overrides] applied, by name; a value that does not parse or is out of range is
         * refused with a message naming the setting.
         */
        fun from(environment: RuntimeValues, overrides: Map<String, String>): RuntimeValues {
            fun int(name: String, current: Int, range: IntRange): Int {
                val raw = overrides[name] ?: return current
                return raw.trim().toIntOrNull()?.takeIf { it in range }
                    ?: throw ApiFailure(
                        400,
                        "INVALID_REQUEST",
                        "$name must be between ${range.first} and ${range.last}",
                    )
            }
            val level = overrides[LOG_LEVEL]?.trim()?.lowercase() ?: environment.logLevel
            if (LOG_LEVEL in overrides && level !in LogSettings.LEVELS) {
                throw ApiFailure(
                    400,
                    "INVALID_REQUEST",
                    "$LOG_LEVEL must be one of ${LogSettings.LEVELS.joinToString()}",
                )
            }
            val learned = overrides[LEARNED_BREAKS]?.trim()?.lowercase() ?: environment.learnedBreaks
            if (LearnedBreaks.of(learned) == null) {
                throw ApiFailure(
                    400,
                    "INVALID_REQUEST",
                    "$LEARNED_BREAKS must be one of ${LearnedBreaks.entries.joinToString { it.setting }}",
                )
            }
            val sessions = int(MAX_SESSIONS, environment.maxSessions, 1..16)
            val tuners = int(HDHR_TUNERS, environment.hdhrTuners, 1..16)
            // The environment's own pair is the server's to live with (startup validated it); only a
            // saved value is held to the rule.
            if ((HDHR_TUNERS in overrides || MAX_SESSIONS in overrides) && tuners > sessions) {
                throw ApiFailure(
                    400,
                    "INVALID_REQUEST",
                    "$HDHR_TUNERS must be between 1 and $MAX_SESSIONS ($sessions)",
                )
            }
            return RuntimeValues(
                level, int(GUIDE_DAYS, environment.guideDays, 1..14),
                int(
                    PLEX_SYNC_MINUTES,
                    environment.plexSyncMinutes,
                    5..1440,
                ),
                tuners, sessions,
                int(
                    RECORDINGS_FREE_GIB,
                    environment.recordingsFreeGib,
                    FREE_GIB_RANGE,
                ),
                int(RECORDINGS_KEEP_DAYS, environment.recordingsKeepDays, KEEP_DAYS_RANGE),
                learned, int(AD_LISTENERS, environment.adListeners, AD_LISTENERS_RANGE),
            )
        }
    }
}

/**
 * What a live session does with a break the server learned from the stream itself, such as a long silent slate, rather
 * than from a marker the provider sent: nothing, note it in the log only, or mark it like any other break. Markers the
 * provider sends are always used.
 */
enum class LearnedBreaks(val setting: String) {
    OFF("off"),
    SHADOW("shadow"),
    ON("on"),
    ;

    companion object {
        fun of(setting: String): LearnedBreaks? = entries.firstOrNull { it.setting == setting.trim().lowercase() }
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
        fun fits(values: Map<String, String>, report: Boolean) = try {
            RuntimeValues.from(environment, values)
            true
        } catch (e: ApiFailure) {
            if (report) {
                problems +=
                    e.message
            }
            false
        }
        // Checked as the set it was saved as, since tuners are held to the saved session cap, not the environment's.
        var kept = if (fits(overrides, report = false)) {
            overrides
        } else {
            overrides.filter { (name, value) -> fits(mapOf(name to value), report = true) }
        }
        if (!fits(kept, report = true)) kept = kept - RuntimeValues.HDHR_TUNERS
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
