package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.MIB
import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.backend.api.ProviderStatus
import dev.jamesrich.sofaking.backend.api.SourceStatus
import dev.jamesrich.sofaking.backend.api.UpdateSource
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.providers.NativeServices
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ProviderRegistry
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.backend.store.deleteSnapshots
import dev.jamesrich.sofaking.backend.store.offeringProtocols
import dev.jamesrich.sofaking.backend.store.pruneOfferings
import dev.jamesrich.sofaking.backend.store.recordProtocol
import dev.jamesrich.sofaking.backend.store.saveSnapshot
import dev.jamesrich.sofaking.backend.store.setManuallyDisabled
import dev.jamesrich.sofaking.backend.store.snapshots
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import dev.jamesrich.sofaking.contract.Channel
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel as Trigger

class ApiFailure(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** How long a provider left out of the configuration, or a source no feed lists, keeps its stored data. */
internal val RETIRED_AFTER: Duration = Duration.ofDays(30)

/**
 * One published snapshot of every provider's channels and sources. [protocols] holds the protocol a stream body was
 * seen to carry; it replaces the protocol the import assigned.
 */
class PublishedFeeds internal constructor(
    internal val feeds: List<FeedSnapshot>,
    internal val protocols: Map<String, String> = emptyMap(),
) {
    private val channelsAndProviders =
        feeds.flatMap { feed -> feed.channels.map { it to feed.providerId } }.distinctBy { it.first.id }
    private val channelById = channelsAndProviders.associate { it.first.id to it.first }
    private val providerByChannel = channelsAndProviders.associate { it.first.id to it.second }

    /** Every provider's sources, the first provider's first. */
    val offerings = feeds.flatMap { it.offerings }.map { offering ->
        protocols[offering.id]?.let { offering.copy(protocol = it) }
            ?: offering
    }
    private val offeringById = offerings.associateBy { it.id }
    private val offeringsByChannel = offerings.groupBy { it.channelId }
    private val channelsRevision = hash(Json.encodeToString(channelsAndProviders.map { it.first }))

    /** Which sources exist decides playbackEligible, and a withdrawal writes no health row. */
    private val offeringsRevision = hash(offerings.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.channelId}" })

    fun offering(id: String): Offering? = offeringById[id]

    fun offeringsOf(channelId: String): List<Offering> = offeringsByChannel[channelId].orEmpty()

    /** The channels of [providerId], or of every provider. */
    fun channels(providerId: String? = null): List<Channel> = if (providerId == null) {
        channelsAndProviders.map { it.first }
    } else {
        channelsAndProviders.filter { it.second == providerId }.map { it.first }
    }

    fun channel(id: String): Channel? = channelById[id]

    fun providerOf(channelId: String): String? = providerByChannel[channelId]

    /** Changes when any channel or source is added, changed or withdrawn. */
    fun revision(): String = "$channelsRevision:$offeringsRevision"
}

/** Provider playlists: the candidate channels and their sources. The lineup decides what the app sees. */
class FeedService(
    val configs: List<FeedConfig>,
    private val store: BackendStore,
    private val http: HttpClient,
    private val clock: Clock,
    /** Governs every upstream fetch made for these feeds: playlists, guides, probes and relayed media. */
    val upstream: UpstreamPolicy = UpstreamPolicy(),
    /** The native service clients behind `type: native` providers; required when any is configured. */
    private val providers: ProviderRegistry? = null,
    /** Where each import is recorded for the portal's Jobs page. */
    private val jobs: Jobs = Jobs(clock),
    /** Runs after each successful import, once the snapshot is published. */
    private val onImported: () -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(FeedService::class.java)
    private val current = AtomicReference(PublishedFeeds(emptyList()))
    private val _statuses = AtomicReference<List<ProviderStatus>>(configs.map { ProviderStatus(it.id) })

    /** The snapshot published now; hold one to read several things consistently. */
    val published: PublishedFeeds get() = current.get()

    /** Each configured provider's last import. */
    val statuses: List<ProviderStatus> get() = _statuses.get()

    /** Guards publishing a snapshot or a protocol; an import fetches and parses outside it. */
    private val mutex = Mutex()

    /** One import at a time per provider. */
    private val importLocks = ConcurrentHashMap<String, Mutex>()
    private val triggers = configs.associate { it.id to Trigger<Unit>(Trigger.CONFLATED) }

    init {
        for (config in configs) {
            jobs.register(
                "import:${config.id}",
                "Import ${config.id}",
                Duration.ofSeconds(config.refreshInterval),
            ) {
                requestRefresh(config.id)
            }
        }
    }

    suspend fun initialize() {
        val stored = store.snapshots()
        val configured = configs.mapTo(HashSet()) { it.id }
        current.set(
            PublishedFeeds(
                stored.filter { it.providerId in configured }.sortedBy { snapshot ->
                    configs.indexOfFirst { it.id == snapshot.providerId }
                },
                store.offeringProtocols(),
            ),
        )
        // With no provider configured nothing tells live data from retired data, so nothing is pruned.
        if (configured.isEmpty()) return
        val cutoff = clock.instant().minus(RETIRED_AFTER)
        val retired = stored.filter { snapshot ->
            snapshot.providerId !in configured &&
                runCatching { java.time.Instant.parse(snapshot.importedAt) }.getOrNull()?.isBefore(cutoff) == true
        }.map { it.providerId }
        if (retired.isNotEmpty()) {
            store.deleteSnapshots(retired)
            log.info("deleted retired provider snapshots providers={}", retired.sorted())
        }
        val kept = stored.filter {
            it.providerId !in retired
        }.flatMapTo(HashSet()) { snapshot -> snapshot.offerings.map { it.id } }
        val pruned = store.pruneOfferings(kept, cutoff)
        if (pruned > 0) {
            log.info("deleted health of retired sources count={} retiredDays={}", pruned, RETIRED_AFTER.toDays())
        }
    }

    /** Records the protocol a probe or media fetch found in the stream body; every later offering view carries it. */
    suspend fun recordProtocol(offeringId: String, protocol: String) {
        mutex.withLock {
            val now = current.get()
            if (now.protocols[offeringId] == protocol) return
            store.recordProtocol(offeringId, protocol, clock.instant())
            current.set(PublishedFeeds(now.feeds, now.protocols + (offeringId to protocol)))
            log.info("source protocol offering={} protocol={}", offeringId, protocol)
        }
    }

    fun config(providerId: String): FeedConfig = configs.first { it.id == providerId }

    /**
     * What to fetch for [offering]: its URL with the provider's headers, or for a native `svc://` source the URL
     * its service client resolves now, with that client's headers. Throws [ProviderFailure]
     * when the service cannot answer.
     */
    suspend fun playable(offering: Offering): ResolvedStream = if (NativeServices.isNative(offering.url)) {
        checkNotNull(providers) { "Native source without a registry" }.resolve(offering)
    } else {
        ResolvedStream(offering.url, config(offering.providerId).headers)
    }

    suspend fun refresh(config: FeedConfig) = importLocks.computeIfAbsent(config.id) { Mutex() }.withLock {
        jobs.record("import:${config.id}") {
            val started = clock.instant()
            runCatchingCancellable {
                val parsed = parse(config)
                val (channels, offerings) = parsed
                require(channels.isNotEmpty() || config.mayBeEmpty) { "Empty playlist requires explicit allowEmpty" }
                val succeededAt = clock.instant().toString()
                val firstSinceStart = statuses.firstOrNull { it.id == config.id }?.lastSuccessAt == null
                val unchanged = publish(config, parsed, succeededAt)
                _statuses.updateAndGet { all ->
                    all.replacing(config.id) {
                        ProviderStatus(
                            config.id,
                            succeededAt,
                            channels = channels.size,
                            sources = offerings.size,
                            skippedEntries = parsed.skippedCount,
                            skipReasons = parsed.skipSummary,
                            notes = parsed.noteSummary,
                        )
                    }
                }
                log.info(
                    "import provider={} channels={} sources={} skipped={} unchanged={} durationMs={}",
                    config.id,
                    channels.size,
                    offerings.size,
                    parsed.skippedCount,
                    unchanged,
                    Duration.between(started, clock.instant()).toMillis(),
                )
                if (parsed.skippedCount > 0) {
                    log.warn("import provider={} skipped malformed entries: {}", config.id, parsed.skipSummary)
                }
                parsed.noteSummary?.let { log.info("import provider={} derived identities: {}", config.id, it) }
                // The first success since start still wakes a lineup run that waits for every provider's first import.
                if (!unchanged || firstSinceStart) onImported()
                JobOutcome(
                    true,
                    "${channels.size} channels, ${offerings.size} sources" + if (unchanged) ", unchanged" else "",
                )
            }.getOrElse { e ->
                _statuses.updateAndGet { all ->
                    all.replacing(config.id) { it.copy(errorCode = "IMPORT_FAILED", error = importError(e)) }
                }
                // The message, with its URLs redacted, tells an auth or rate limit from an outage.
                log.warn(
                    "import provider={} failed class={} transient={} message=\"{}\" durationMs={}",
                    config.id,
                    e.javaClass.simpleName,
                    (e as? ProviderFailure)?.transient ?: true,
                    redactedMessage(e),
                    Duration.between(started, clock.instant()).toMillis(),
                )
                JobOutcome(false, "IMPORT_FAILED (${e.javaClass.simpleName})")
            }
        }
    }

    /** The provider's channels and sources: from its service client, or from its playlist. */
    private suspend fun parse(config: FeedConfig): ParsedPlaylist {
        if (config.native) {
            return checkNotNull(providers) { "Native providers are configured without a registry" }.channels(config)
        }
        val url = checkNotNull(config.playlistUrl)
        val playlist = http.withUpstreamResponse(url, url, config.headers, policy = upstream) { response ->
            require(response.status == HttpStatusCode.OK) { "Feed fetch failed" }
            val bytes = response.bodyAsChannel().readBuffer(PLAYLIST_LIMIT + 1L).readByteArray()
            require(bytes.size <= PLAYLIST_LIMIT) { "Feed exceeds size limit" }
            bytes.toString(Charsets.UTF_8)
        }
        return FeedParser.playlist(config.id, playlist, config.defaultProtocol, config.renewable, config.playlistUrl)
    }

    /**
     * Publishes the import [parsed] of [config]'s provider, keeping the channels it no longer lists; true when that
     * changes nothing, so nothing is rewritten and nothing downstream wakes.
     */
    private suspend fun publish(config: FeedConfig, parsed: ParsedPlaylist, importedAt: String): Boolean =
        // Only publishing takes the shared lock, so a slow provider holds up neither another's import nor a protocol
        // record.
        mutex.withLock {
            val previous = current.get().feeds.find { it.providerId == config.id }
            val retainedChannels = (parsed.channels + previous?.channels.orEmpty()).distinctBy { it.id }
            val unchanged = previous != null && previous.channels.toSet() == retainedChannels.toSet() &&
                previous.offerings.toSet() == parsed.offerings.toSet()
            if (!unchanged) {
                val snapshot = FeedSnapshot(config.id, retainedChannels, parsed.offerings, importedAt = importedAt)
                store.saveSnapshot(snapshot)
                val now = current.get()
                current.set(
                    PublishedFeeds(
                        (now.feeds.filterNot { it.providerId == config.id } + snapshot).sortedBy { item ->
                            configs.indexOfFirst { it.id == item.providerId }
                        },
                        now.protocols,
                    ),
                )
            }
            unchanged
        }

    /** Asks the provider's loop to import soon; repeated requests before it runs coalesce into one. */
    fun requestRefresh(providerId: String) {
        triggers[providerId]?.trySend(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // select { onTimeout }
    fun start(scope: CoroutineScope): List<Job> = configs.map { config ->
        scope.launch(CoroutineName("feed-refresh:${config.id}")) {
            val trigger = triggers.getValue(config.id)
            while (isActive) {
                refresh(config)
                select<Unit> {
                    trigger.onReceive {}
                    onTimeout(config.refreshInterval * MS_PER_SECOND) {}
                }
            }
        }
    }

    suspend fun sources(): List<SourceStatus> {
        val states = store.allHealth()
        return published.offerings.map { offering ->
            val state = checkNotNull(states[offering.id])
            SourceStatus(
                offering.id,
                offering.channelId,
                offering.providerId,
                state.status.name,
                state.manuallyDisabled,
                state.lastQualifiedAt?.toString(),
                state.nextCheckAt?.toString(),
            )
        }
    }

    suspend fun updateSource(id: String, update: UpdateSource): SourceStatus {
        val source =
            published.offering(id) ?: throw ApiFailure(HttpStatusCode.NotFound, "SOURCE_NOT_FOUND", "Unknown source")
        store.setManuallyDisabled(id, update.manuallyDisabled)
        val state = checkNotNull(store.allHealth()[id])
        return SourceStatus(
            id,
            source.channelId,
            source.providerId,
            state.status.name,
            state.manuallyDisabled,
            state.lastQualifiedAt?.toString(),
            state.nextCheckAt?.toString(),
        )
    }
}

/** The most a playlist download may hold. */
private const val PLAYLIST_LIMIT = 16 * MIB

/** This list with the status of provider [id] replaced by what [update] makes of it. */
private fun List<ProviderStatus>.replacing(id: String, update: (ProviderStatus) -> ProviderStatus) =
    map { if (it.id == id) update(it) else it }

/** The SHA-256 of [value]'s UTF-8 bytes, hex. */
fun hash(value: String): String = hash(value.toByteArray())

/** The SHA-256 of [bytes], hex. */
fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
