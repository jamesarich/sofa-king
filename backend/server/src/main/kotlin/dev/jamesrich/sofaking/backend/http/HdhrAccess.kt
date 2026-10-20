package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.HdhrAccessStatus
import dev.jamesrich.sofaking.backend.api.HdhrRefusedClient
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.hdhrAllowed
import dev.jamesrich.sofaking.backend.store.saveHdhrAllowed
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A client the HDHomeRun emulation refused: its address, when it last asked, and the path it asked for. */
data class HdhrRefusal(val address: String, val at: Instant, val path: String)

/**
 * Who may reach the HDHomeRun emulation, which takes no token: the addresses in `SOFA_HDHR_ALLOW` ([configured]) and
 * those an admin allowed from the portal, which are kept in the database and apply at once. With neither, a loopback
 * bind ([openWhenEmpty]) admits everyone and any other bind admits nobody. The newest refused addresses are kept in
 * memory, so the portal can offer to allow the Plex server that just tried.
 */
class HdhrAccess(
    val configured: List<String>,
    private val openWhenEmpty: Boolean,
    private val store: BackendStore,
    private val clock: Clock,
    private val keepRefusals: Int = 10,
) {
    private val fromEnvironment = AddressAllowList(configured)
    private val writes = Mutex()
    @Volatile var allowed: List<String> = emptyList()
        private set
    @Volatile private var fromPortal = AddressAllowList(emptyList())
    private val refused = ConcurrentHashMap<String, HdhrRefusal>()

    /** Reads the addresses the portal allowed; an entry that no longer parses is dropped. */
    suspend fun load() {
        allowed = store.hdhrAllowed().filter { runCatching { AddressAllowList(listOf(it)) }.isSuccess }
        fromPortal = AddressAllowList(allowed)
    }

    /** Whether [remote] may use the emulation; a refusal is remembered with [path]. */
    fun admits(remote: String, path: String): Boolean {
        val ok = if (configured.isEmpty() && allowed.isEmpty()) openWhenEmpty else isAllowed(remote)
        if (!ok) {
            refused[remote] = HdhrRefusal(remote, clock.instant(), path)
            if (refused.size > keepRefusals) refused.values.minByOrNull { it.at }?.let { refused.remove(it.address) }
        }
        return ok
    }

    /** Whether every address is admitted: a loopback bind with no address configured or allowed. */
    val open: Boolean get() = configured.isEmpty() && allowed.isEmpty() && openWhenEmpty

    /** The addresses refused since the start that are still refused, newest first. */
    fun refusals(): List<HdhrRefusal> = refused.values.filterNot { r -> isAllowed(r.address) }.sortedByDescending { it.at }

    /** What the admin API and the portal show; [enabled] says whether the emulation is served at all. */
    fun status(enabled: Boolean) = HdhrAccessStatus(
        enabled, open, configured, allowed, refusals().map { HdhrRefusedClient(it.address, it.at.toString(), it.path) },
    )

    private fun isAllowed(address: String) =
        (configured.isNotEmpty() && fromEnvironment.allows(address)) || (allowed.isNotEmpty() && fromPortal.allows(address))

    /**
     * Replaces the portal's addresses with [entries], IP literals or CIDR blocks, and applies them at once. Fails with
     * the entry that does not parse; nothing changes then.
     */
    suspend fun replace(entries: List<String>): Unit = writes.withLock {
        val cleaned = entries.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(cleaned.size <= MAX_ALLOWED) { "Allow at most $MAX_ALLOWED addresses" }
        val list = AddressAllowList(cleaned)
        store.saveHdhrAllowed(cleaned)
        allowed = cleaned
        fromPortal = list
    }

    suspend fun allow(entry: String) = replace(allowed + entry)

    suspend fun remove(entry: String) = replace(allowed - entry.trim())

    companion object {
        const val MAX_ALLOWED = 64
    }
}
