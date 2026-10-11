package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideChannelInfo
import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.providers.ProviderRegistry
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.StoredGuide
import dev.jamesrich.sofaking.backend.store.guides
import dev.jamesrich.sofaking.backend.store.programmes
import dev.jamesrich.sofaking.backend.store.saveGuide
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Independent XMLTV sources. Each guide refreshes on its own interval and fails on its own;
 * only programmes for channels that [wanted] names are retained, in SQLite.
 */
class GuideService(
    val configs: List<GuideConfig>,
    private val store: BackendStore,
    private val http: HttpClient,
    private val clock: Clock,
    private val upstream: UpstreamPolicy = UpstreamPolicy(),
    /** The native service clients whose own guides back `provider` guides. */
    private val providers: ProviderRegistry? = null,
    /** Guide id to the XMLTV channel ids the lineup maps onto it. */
    private val wanted: () -> Map<String, Set<String>>,
) {
    private class Published(val guides: Map<String, StoredGuide>) {
        val revision = hash(guides.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.importedAt}" })
        val channelIndex: Map<String, Map<String, GuideChannel>> = guides.mapValues { (_, guide) -> guide.channels.associateBy { it.id } }
    }

    private val log = LoggerFactory.getLogger(GuideService::class.java)
    private val published = AtomicReference(Published(emptyMap()))
    private val statuses = AtomicReference(configs.map { GuideStatus(it.id) })
    private val locks = configs.associate { it.id to Mutex() }
    private val triggers = configs.associate { it.id to Channel<Unit>(Channel.CONFLATED) }

    suspend fun initialize() {
        val stored = store.guides().filterKeys { id -> configs.any { it.id == id } }
        published.set(Published(stored))
        statuses.set(configs.map { config ->
            stored[config.id]?.let { GuideStatus(config.id, it.importedAt.toString(), channels = it.channels.size, programmes = it.programmes) } ?: GuideStatus(config.id)
        })
    }

    fun statuses(): List<GuideStatus> = statuses.get()

    fun config(id: String): GuideConfig? = configs.firstOrNull { it.id == id }

    /** Changes with every successful import of any guide. */
    fun revision(): String = published.get().revision

    /** When the retained programmes of each mapped channel end, keyed by guide id then channel id. */
    fun coverage(): Map<String, Map<String, Instant>> = published.get().guides.mapValues { it.value.programmeEnds }

    fun channels(guideId: String): List<GuideChannel> = published.get().guides[guideId]?.channels.orEmpty()

    fun channel(guideId: String, channelId: String): GuideChannel? = published.get().channelIndex[guideId]?.get(channelId)

    fun searchChannels(guideId: String, query: String?, limit: Int): List<GuideChannelInfo> {
        val needle = query?.trim()?.lowercase().orEmpty()
        return channels(guideId).asSequence()
            .filter { needle.isEmpty() || it.id.lowercase().contains(needle) || it.displayName.lowercase().contains(needle) }
            .take(limit).map { GuideChannelInfo(guideId, it.id, it.displayName, it.icon) }.toList()
    }

    /** Configured guides that list [channelId], in configuration order. */
    fun guidesListing(channelId: String): List<String> {
        val index = published.get().channelIndex
        return configs.map { it.id }.filter { index[it]?.containsKey(channelId) == true }
    }

    suspend fun programmes(guideId: String, channelIds: Collection<String>, from: Instant, to: Instant): Map<String, List<GuideProgramme>> =
        if (guideId !in locks) emptyMap() else store.programmes(guideId, channelIds, from, to)

    suspend fun refresh(config: GuideConfig) = locks.getValue(config.id).withLock {
        val started = clock.instant()
        try {
            val wantedIds = wanted()[config.id].orEmpty()
            val parsed = if (config.provider != null) {
                val client = checkNotNull(providers?.client(config.provider)) { "No client for provider ${config.provider}" }
                checkNotNull(client.guide(wantedIds)) { "Service has no guide" }
            } else {
                val url = checkNotNull(config.url)
                // Guides run to tens of megabytes; only socket inactivity bounds the download, never a total request timeout.
                http.withUpstreamResponse(url, url, config.headers, unbounded = true, policy = upstream) { response ->
                    require(response.status.value == 200) { "Guide fetch failed" }
                    val body = response.bodyAsChannel()
                    withContext(Dispatchers.IO) { body.toInputStream().use { FeedParser.guide(it, config.url) { id -> id in wantedIds } } }
                }
            }
            val importedAt = clock.instant()
            store.saveGuide(config.id, importedAt, parsed.channels, parsed.programmes)
            val ends = parsed.programmes.groupBy { it.channelId }.mapValues { (_, rows) -> rows.maxOf { it.endsAt } }
            val stored = StoredGuide(config.id, importedAt, parsed.channels, ends, parsed.programmes.size)
            published.updateAndGet { Published(it.guides + (config.id to stored)) }
            statuses.updateAndGet { list ->
                list.map {
                    if (it.id == config.id) GuideStatus(config.id, importedAt.toString(), channels = parsed.channels.size, programmes = parsed.programmes.size) else it
                }
            }
            log.info(
                "guide import guide={} channels={} programmes={} wanted={} controlChars={} durationMs={}",
                config.id, parsed.channels.size, parsed.programmes.size, wantedIds.size, parsed.controlCharacters, Duration.between(started, clock.instant()).toMillis(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statuses.updateAndGet { list -> list.map { if (it.id == config.id) it.copy(errorCode = "IMPORT_FAILED") else it } }
            log.warn(
                "guide import guide={} failed class={} message=\"{}\" durationMs={}",
                config.id, e.javaClass.simpleName, redactedMessage(e), Duration.between(started, clock.instant()).toMillis(),
            )
        }
    }

    /** Asks the guide's loop to refresh soon; repeated requests before it runs coalesce into one. */
    fun requestRefresh(guideId: String) {
        triggers[guideId]?.trySend(Unit)
    }

    fun start(scope: CoroutineScope): List<Job> = configs.map { config ->
        scope.launch {
            val trigger = triggers.getValue(config.id)
            while (isActive) {
                refresh(config)
                select<Unit> {
                    trigger.onReceive {}
                    onTimeout(config.refreshSeconds * 1000) {}
                }
            }
        }
    }
}

private val urlInMessage = Regex("""https?://[^\s"'\]\[<>]+""")

/** The exception's message with every URL (and so any query token) replaced by `<url>`, on one line, bounded. */
internal fun redactedMessage(e: Throwable): String {
    val message = e.message?.takeIf { it.isNotBlank() } ?: e.cause?.message ?: return ""
    return urlInMessage.replace(message, "<url>").replace(Regex("\\s+"), " ").trim().take(300)
}
