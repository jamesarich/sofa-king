package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.backend.api.GuideChannelInfo
import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.hash
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.providers.ProviderRegistry
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.StoredGuide
import dev.jamesrich.sofaking.backend.store.guides
import dev.jamesrich.sofaking.backend.store.programs
import dev.jamesrich.sofaking.backend.store.saveGuide
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Independent XMLTV sources. Each guide refreshes on its own interval and fails on its own;
 * only programs for channels that [wanted] names are retained, in SQLite.
 */
class GuideService(
    val configs: List<GuideConfig>,
    private val store: BackendStore,
    private val http: HttpClient,
    private val clock: Clock,
    private val upstream: UpstreamPolicy = UpstreamPolicy(),
    /** The native service clients whose own guides back `provider` guides. */
    private val providers: ProviderRegistry? = null,
    /** Runs after each successful import. */
    private val onImported: () -> Unit = {},
    /** Where each import is recorded for the portal's Jobs page. */
    private val jobs: Jobs = Jobs(clock),
    /** Guide id to the XMLTV channel ids the lineup maps onto it. */
    private val wanted: () -> Map<String, Set<String>>,
) {
    private class Published(val guides: Map<String, StoredGuide>) {
        val revision = hash(guides.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.importedAt}" })
        val channelIndex: Map<String, Map<String, GuideChannel>> = guides.mapValues { (_, guide) ->
            guide.channels.associateBy { it.id }
        }
    }

    private val log = LoggerFactory.getLogger(GuideService::class.java)
    private val published = AtomicReference(Published(emptyMap()))
    private val _statuses = AtomicReference(configs.map { GuideStatus(it.id) })
    private val fetcher = GuideFetcher(http, upstream, providers)
    private val locks = configs.associate { it.id to Mutex() }

    init {
        for (config in configs) {
            jobs.register(
                "guide:${config.id}",
                "Guide ${config.id}",
                Duration.ofSeconds(config.refreshSeconds),
            ) {
                requestRefresh(config.id)
            }
        }
    }
    private val triggers = configs.associate { it.id to Channel<Unit>(Channel.CONFLATED) }

    suspend fun initialize() {
        val stored = store.guides().filterKeys { id -> configs.any { it.id == id } }
        published.set(Published(stored))
        _statuses.set(
            configs.map { config ->
                stored[config.id]?.let {
                    GuideStatus(
                        config.id,
                        it.importedAt.toString(),
                        channels = it.channels.size,
                        programs = it.programs,
                    )
                }
                    ?: GuideStatus(config.id)
            },
        )
    }

    /** Each configured guide's last import. */
    val statuses: List<GuideStatus> get() = _statuses.get()

    fun config(id: String): GuideConfig? = configs.firstOrNull { it.id == id }

    /** Changes with every successful import of any guide. */
    fun revision(): String = published.get().revision

    /** When the retained programs of each mapped channel end, keyed by guide id then channel id. */
    val coverage: Map<String, Map<String, Instant>> get() = published.get().guides.mapValues { it.value.programEnds }

    fun channels(guideId: String): List<GuideChannel> = published.get().guides[guideId]?.channels.orEmpty()

    fun channel(guideId: String, channelId: String): GuideChannel? =
        published.get().channelIndex[guideId]?.get(channelId)

    fun searchChannels(guideId: String, query: String?, limit: Int): List<GuideChannelInfo> {
        val needle = query?.trim()?.lowercase().orEmpty()
        return channels(guideId).asSequence()
            .filter {
                needle.isEmpty() || it.id.lowercase().contains(needle) || it.displayName.lowercase().contains(needle)
            }
            .take(limit).map { GuideChannelInfo(guideId, it.id, it.displayName, it.icon) }.toList()
    }

    /** Configured guides that list [channelId], in configuration order. */
    fun guidesListing(channelId: String): List<String> {
        val index = published.get().channelIndex
        return configs.map { it.id }.filter { index[it]?.containsKey(channelId) == true }
    }

    suspend fun programs(
        guideId: String,
        channelIds: Collection<String>,
        from: Instant,
        to: Instant,
    ): Map<String, List<GuideProgram>> =
        if (guideId !in locks) emptyMap() else store.programs(guideId, channelIds, from, to)

    suspend fun refresh(config: GuideConfig) = locks.getValue(config.id).withLock {
        jobs.record("guide:${config.id}") {
            val started = clock.instant()
            runCatchingCancellable {
                val wantedIds = wanted()[config.id].orEmpty()
                val parsed = fetcher.fetch(config, wantedIds).shifted(config.offsetMinutes)
                val importedAt = clock.instant()
                store.saveGuide(config.id, importedAt, parsed.channels, parsed.programs)
                val ends = parsed.programs.groupBy { it.channelId }.mapValues { (_, rows) -> rows.maxOf { it.endsAt } }
                val stored = StoredGuide(config.id, importedAt, parsed.channels, ends, parsed.programs.size)
                published.updateAndGet { Published(it.guides + (config.id to stored)) }
                _statuses.updateAndGet { all ->
                    all.replacing(config.id) {
                        GuideStatus(
                            config.id,
                            importedAt.toString(),
                            channels = parsed.channels.size,
                            programs = parsed.programs.size,
                        )
                    }
                }
                log.info(
                    "guide import guide={} channels={} programs={} wanted={} controlChars={} durationMs={}",
                    config.id,
                    parsed.channels.size,
                    parsed.programs.size,
                    wantedIds.size,
                    parsed.controlCharacters,
                    Duration.between(started, clock.instant()).toMillis(),
                )
                onImported()
                JobOutcome(true, "${parsed.channels.size} channels, ${parsed.programs.size} programs")
            }.getOrElse { e ->
                _statuses.updateAndGet { all ->
                    all.replacing(config.id) { it.copy(errorCode = "IMPORT_FAILED", error = importError(e)) }
                }
                log.warn(
                    "guide import guide={} failed class={} message=\"{}\" durationMs={}",
                    config.id,
                    e.javaClass.simpleName,
                    redactedMessage(e),
                    Duration.between(started, clock.instant()).toMillis(),
                )
                JobOutcome(false, "IMPORT_FAILED (${e.javaClass.simpleName})")
            }
        }
    }

    /** Asks the guide's loop to refresh soon; repeated requests before it runs coalesce into one. */
    fun requestRefresh(guideId: String) {
        triggers[guideId]?.trySend(Unit)
    }

    fun start(scope: CoroutineScope): List<Job> = configs.map { config ->
        scope.launch(CoroutineName("guide-refresh:${config.id}")) {
            val trigger = triggers.getValue(config.id)
            while (isActive) {
                refresh(config)
                withTimeoutOrNull(config.refreshSeconds * MS_PER_SECOND) { trigger.receive() }
            }
        }
    }
}

/** This guide with every program moved by [minutes]; itself when that is 0. */
internal fun ParsedGuide.shifted(minutes: Int): ParsedGuide = if (minutes == 0) {
    this
} else {
    val by = Duration.ofMinutes(minutes.toLong())
    copy(programs = programs.map { it.copy(startsAt = it.startsAt.plus(by), endsAt = it.endsAt.plus(by)) })
}

/** This list with the status of guide [id] replaced by what [update] makes of it. */
private fun List<GuideStatus>.replacing(id: String, update: (GuideStatus) -> GuideStatus) =
    map { if (it.id == id) update(it) else it }

/** Fetches a guide: from its service's client for a `provider` guide, else as XMLTV from its URL. */
private class GuideFetcher(
    private val http: HttpClient,
    private val upstream: UpstreamPolicy,
    private val providers: ProviderRegistry?,
) {
    /** The guide, holding the programs of the channels in [wanted] only. */
    suspend fun fetch(config: GuideConfig, wanted: Set<String>): ParsedGuide {
        if (config.provider != null) {
            val client =
                checkNotNull(providers?.client(config.provider)) { "No client for provider ${config.provider}" }
            return checkNotNull(client.guide(wanted)) { "Service has no guide" }
        }
        val url = checkNotNull(config.url)
        // Guides run to tens of megabytes; only socket inactivity bounds the download, never a total request timeout.
        return http.withUpstreamResponse(url, url, config.headers, unbounded = true, policy = upstream) { response ->
            require(response.status == HttpStatusCode.OK) { "Guide fetch failed" }
            val body = response.bodyAsChannel()
            withContext(Dispatchers.IO) {
                body.toInputStream().use { FeedParser.guide(it, config.url) { id -> id in wanted } }
            }
        }
    }
}

private val urlInMessage = Regex("""https?://[^\s"'\]\[<>]+""")
private val whitespace = Regex("\\s+")
private const val MESSAGE_LIMIT = 300

/** Why an import failed, for the portal: its message with URLs redacted, or the exception's name without one. */
internal fun importError(e: Throwable): String = redactedMessage(e).ifEmpty { e.javaClass.simpleName }

/** The exception's message with every URL (and so any query token) replaced by `<url>`, on one line, bounded. */
internal fun redactedMessage(e: Throwable): String =
    redactedMessage(e.message?.takeIf { it.isNotBlank() } ?: e.cause?.message ?: return "")

/**
 * [message] with every URL replaced by `<url>`, on one line, bounded; FFmpeg's and the
 * fetchers' lines carry tokens in URLs.
 */
internal fun redactedMessage(message: String): String =
    urlInMessage.replace(message, "<url>").replace(whitespace, " ").trim().take(MESSAGE_LIMIT)
