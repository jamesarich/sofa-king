package dev.jamesrich.sofaking.backend.settings

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.runtimeSettings
import dev.jamesrich.sofaking.backend.store.saveRuntimeSettings
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The settings the server changes without a restart, by variable name: the registry's runtime [Settings], each with
 * the text it is kept in. The typed accessors are what the server's consumers read.
 * [plexSyncMinutes] is kept whether or not Plex sync is configured, so it is there when it is.
 */
data class RuntimeValues(private val values: Map<String, String>) {
    private fun int(def: SettingDef) = values.getValue(def.name).toInt()

    val logLevel: String get() = values.getValue(Settings.LOG_LEVEL.name)
    val guideDays: Int get() = int(Settings.XMLTV_DAYS)
    val plexSyncMinutes: Int get() = int(Settings.PLEX_SYNC_MINUTES)
    val hdhrTuners: Int get() = int(Settings.HDHR_TUNERS)
    val maxSessions: Int get() = int(Settings.MAX_SESSIONS)
    val recordingsFreeGib: Int get() = int(Settings.RECORDINGS_FREE_GIB)
    val recordingsKeepDays: Int get() = int(Settings.RECORDINGS_KEEP_DAYS)
    val learnedBreaks: String get() = values.getValue(Settings.LEARNED_BREAKS.name)
    val adListeners: Int get() = int(Settings.AD_LISTENERS)

    /** The values by their variable name, as the settings page and the database name them. */
    fun byName(): Map<String, String> = values

    companion object {
        /** The server's defaults, for a runtime built without a [ServerConfig]. */
        val DEFAULT = RuntimeValues(Settings.runtime.associate { it.name to it.default.orEmpty() })

        /** What the environment set, the values the server starts with until a saved setting replaces one. */
        fun of(config: ServerConfig) =
            RuntimeValues(Settings.runtime.associate { it.name to it.show(config).toString() })

        /**
         * [environment] with the saved [overrides] applied, by name; a value that does not parse or is out of range is
         * refused with a message naming the setting.
         */
        fun from(environment: RuntimeValues, overrides: Map<String, String>): RuntimeValues {
            val values = Settings.runtime.associate { def ->
                val raw = overrides[def.name]
                def.name to if (raw == null) {
                    environment.values.getValue(def.name)
                } else {
                    def.normalize(raw) ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", def.failure)
                }
            }
            val result = RuntimeValues(values)
            // The environment's own pair is the server's to live with (startup validated it); only a
            // saved value is held to the rule.
            val tuners = Settings.HDHR_TUNERS.name
            val sessions = Settings.MAX_SESSIONS.name
            if ((tuners in overrides || sessions in overrides) && result.hdhrTuners > result.maxSessions) {
                throw ApiFailure(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    "$tuners must be between 1 and $sessions (${result.maxSessions})",
                )
            }
            return result
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
        if (!fits(kept, report = true)) kept = kept - Settings.HDHR_TUNERS.name
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
