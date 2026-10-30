package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.MIB
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ProviderHttp
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.settings.RuntimeSettings
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What the catalog knows about one downloaded file. */
@Serializable
data class IptvOrgFileState(
    val etag: String? = null,
    val lastModified: String? = null,
    val checkedAt: String? = null,
    val changedAt: String? = null,
    val count: Int = 0,
)

@Serializable
internal data class IptvOrgState(
    val files: Map<String, IptvOrgFileState> = emptyMap(),
    val attemptedAt: String? = null,
    val ok: Boolean? = null,
    val notes: List<String> = emptyList(),
)

/** The portal's and API's view of the downloads and the region in force. */
@Serializable
data class IptvOrgStatus(
    val metadata: Boolean,
    val streams: Boolean,
    val region: IptvOrgRegion,
    val attemptedAt: String? = null,
    val checkedAt: String? = null,
    val ok: Boolean? = null,
    val notes: List<String> = emptyList(),
    val nextDueAt: String? = null,
    val channels: Int = 0,
    val logos: Int = 0,
    val blocklisted: Int = 0,
    val streamCount: Int = 0,
    val matched: Int = 0,
    val proposals: Int = 0,
    val held: Int = 0,
)

/**
 * The iptv-org community database (channels, logos, blocklist and time zones, plus streams when the streams provider
 * is on), downloaded weekly into the data directory with conditional requests. A failed or implausible download keeps
 * the last good copy. The metadata matching and the streams provider both read [data].
 */
class IptvOrgCatalog(
    private val directory: Path,
    private val http: ProviderHttp,
    private val clock: Clock,
    private val settings: () -> IptvOrgSettings,
    private val jobs: Jobs,
    private val baseUrl: String = BASE_URL,
    private val zone: () -> ZoneId = { clock.zone },
    /** Runs after a download changed a file or a setting changed. */
    private val onChanged: () -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(IptvOrgCatalog::class.java)
    private val lock = Mutex()
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val forced = AtomicBoolean()
    private val current = AtomicReference(IptvOrgData.EMPTY)

    @Volatile private var state = IptvOrgState()

    init {
        jobs.register(JOB, "iptv-org database", INTERVAL) { request(force = true) }
    }

    /** The parsed files; empty until a download or an earlier copy on disk. */
    val data: IptvOrgData get() = current.get()

    /** Reads the copy on disk, if any. */
    fun load() {
        state = readState(directory)
        current.set(readData(directory))
    }

    /** The region in force for streams: the setting, else the server's time zone, else US. */
    fun region(): IptvOrgRegion = effectiveRegion(settings().region, zone(), data.timezones)

    /** Asks the loop to look now; [force] downloads even when a copy is current, and a later plain ask keeps it. */
    fun request(force: Boolean = false) {
        if (force) forced.set(true)
        trigger.trySend(Unit)
    }

    /**
     * Runs the loop that downloads when due, and whenever [request] is called. When one of the iptv-org [runtime]
     * settings changes, see [settingsChanged].
     */
    fun start(scope: CoroutineScope, runtime: RuntimeSettings) {
        var seen = runtime.current.iptvOrg
        runtime.onChange { values ->
            if (values.iptvOrg != seen) {
                seen = values.iptvOrg
                settingsChanged()
            }
        }
        trigger.trySend(Unit)
        scope.launch(CoroutineName("iptv-org")) {
            while (isActive) {
                runRequested(CHECK_EVERY)
            }
        }
    }

    /**
     * Fetches at once when a switch now needs a file that is not on disk, such as `streams.json` after the metadata
     * download; a change of region only reads the files already there. Either way [onChanged] runs.
     */
    internal fun settingsChanged() {
        val now = settings()
        if (now.enabled && wanted(now).any { !Files.exists(directory.resolve(it)) }) request(force = true)
        onChanged()
    }

    /**
     * Waits up to [timeout] for a request, then downloads when it was forced or a download is due. False when nothing
     * was asked.
     */
    internal suspend fun runRequested(timeout: Duration = Duration.ZERO): Boolean {
        val asked = if (timeout.isZero) {
            trigger.tryReceive().isSuccess
        } else {
            withTimeoutOrNull(timeout.toMillis()) { trigger.receive() } != null
        }
        val force = asked && forced.getAndSet(false)
        runCatchingCancellable { if (settings().enabled && (force || due())) refresh() }
            .onFailure { log.warn("iptv-org download failed class={}", it.javaClass.simpleName) }
        return asked
    }

    /** Whether a week has passed since the last attempt, a failed one is six hours old, or a needed file is missing. */
    fun due(): Boolean {
        val attempted = state.attemptedAt?.let(Instant::parse)
        val missing = wanted(settings()).any { !Files.exists(directory.resolve(it)) }
        return attempted == null || clock.instant() >= attempted.plus(if (missing) RETRY else wait)
    }

    private val wait: Duration get() = if (state.ok == false) RETRY else INTERVAL

    /**
     * Downloads each wanted file, then parses what is on disk. Nothing is fetched while both parts are off. Answers
     * how it went for the Jobs page.
     */
    suspend fun refresh(): JobOutcome = lock.withLock {
        val now = settings()
        var outcome = JobOutcome(true, "off")
        if (now.enabled) {
            jobs.record(JOB) {
                Files.createDirectories(directory)
                val started = clock.instant()
                val results = wanted(now).associateWith { name ->
                    download(name, state.files[name] ?: IptvOrgFileState(), started)
                }
                val failed = results.values.any { it.failed }
                val notes = results.values.map { it.note }
                state = IptvOrgState(results.mapValues { it.value.state }, started.toString(), !failed, notes)
                writeState(directory, state)
                current.set(readData(directory))
                if (results.values.any { it.changed }) onChanged()
                outcome = JobOutcome(!failed, notes.joinToString("; "))
                outcome
            }
        }
        outcome
    }

    private class Downloaded(val state: IptvOrgFileState, val note: String, val changed: Boolean, val failed: Boolean)

    private suspend fun download(name: String, kept: IptvOrgFileState, at: Instant): Downloaded {
        val have = Files.exists(directory.resolve(name))
        val checked = kept.copy(checkedAt = at.toString())
        return when (val fetch = fetch(name, kept, have)) {
            Fetch.Unchanged -> Downloaded(checked, "$name: unchanged", changed = false, failed = false)

            is Fetch.Failed -> Downloaded(checked, "$name: ${fetch.note}", changed = false, failed = true)

            is Fetch.Rows -> {
                writeAtomically(directory, name, fetch.response.body)
                val next = IptvOrgFileState(
                    fetch.response.etag,
                    fetch.response.lastModified,
                    at.toString(),
                    at.toString(),
                    fetch.count,
                )
                Downloaded(next, "$name: ${fetch.count} entries", changed = true, failed = false)
            }
        }
    }

    private suspend fun fetch(name: String, kept: IptvOrgFileState, have: Boolean): Fetch {
        val headers = buildMap {
            if (have) {
                kept.etag?.let { put("If-None-Match", it) }
                kept.lastModified?.let { put("If-Modified-Since", it) }
            }
        }
        val response = try {
            http.get("$baseUrl/$name", headers, LIMIT)
        } catch (e: ProviderFailure) {
            return Fetch.Failed(e.message.orEmpty())
        }
        return when {
            response.notModified && have -> Fetch.Unchanged
            !response.ok -> Fetch.Failed("HTTP ${response.status}")
            else -> checkedRows(response, name in GUARDED, if (have) kept.count else 0)
        }
    }

    /** The download state and counts; the match counts are the caller's to add. */
    fun status(): IptvOrgStatus {
        val now = settings()
        val data = data
        val attempted = state.attemptedAt?.let(Instant::parse)
        return IptvOrgStatus(
            metadata = now.metadata,
            streams = now.streams,
            region = region(),
            attemptedAt = state.attemptedAt,
            checkedAt = state.files.values.mapNotNull { it.checkedAt }.maxOrNull(),
            ok = state.ok,
            notes = state.notes,
            nextDueAt = attempted?.plus(wait)?.toString()?.takeIf { now.enabled },
            channels = data.channels.size,
            logos = data.logos.size,
            blocklisted = data.blocklist.size,
            streamCount = data.streams.size,
        )
    }

    companion object {
        const val JOB = "iptv-org"
        const val BASE_URL = "https://iptv-org.github.io/api"
        private val BASE_FILES = listOf("channels.json", "logos.json", "blocklist.json", "timezones.json")

        /** Files whose entry count must not fall by more than half between downloads. */
        private val GUARDED = setOf("channels.json", "streams.json")
        private const val LIMIT = 64 * MIB
        val INTERVAL: Duration = Duration.ofDays(7)
        private val RETRY: Duration = Duration.ofHours(6)
        private val CHECK_EVERY: Duration = Duration.ofHours(1)

        private fun wanted(settings: IptvOrgSettings): List<String> =
            if (settings.streams) BASE_FILES + "streams.json" else BASE_FILES
    }
}
