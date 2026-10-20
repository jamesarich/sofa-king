package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.backend.api.ProviderStatus
import dev.jamesrich.sofaking.backend.api.SourceStatus
import dev.jamesrich.sofaking.backend.api.UpdateSource
import dev.jamesrich.sofaking.backend.providers.NativeServices
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ProviderRegistry
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as Trigger
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

class ApiFailure(
    val status: Int,
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
) : RuntimeException(message)

/** How long a provider left out of the configuration, or a source no feed lists, keeps its stored data. */
internal val RETIRED_AFTER: Duration = Duration.ofDays(30)

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
    /** [protocols] holds the protocol a stream body was seen to carry; it replaces the protocol the import assigned. */
    private class Published(
        val feeds: List<FeedSnapshot>,
        val protocols: Map<String, String> = emptyMap(),
    ) {
        val channels = feeds.flatMap { feed -> feed.channels.map { it to feed.providerId } }.distinctBy { it.first.id }
        val channelById = channels.associate { it.first.id to it.first }
        val providerByChannel = channels.associate { it.first.id to it.second }
        val offerings = feeds.flatMap { it.offerings }.map { offering -> protocols[offering.id]?.let { offering.copy(protocol = it) } ?: offering }
        val offeringById = offerings.associateBy { it.id }
        val offeringsByChannel = offerings.groupBy { it.channelId }
        val channelsRevision = hash(Json.encodeToString(channels.map { it.first }))

        /** Which sources exist decides playbackEligible, and a withdrawal writes no health row. */
        val offeringsRevision = hash(offerings.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.channelId}" })
    }

    private val log = LoggerFactory.getLogger(FeedService::class.java)
    private val published = AtomicReference(Published(emptyList()))
    private val statuses = AtomicReference<List<ProviderStatus>>(configs.map { ProviderStatus(it.id) })
    /** Guards publishing a snapshot or a protocol; an import fetches and parses outside it. */
    private val mutex = Mutex()
    /** One import at a time per provider. */
    private val importLocks = ConcurrentHashMap<String, Mutex>()
    private val triggers = configs.associate { it.id to Trigger<Unit>(Trigger.CONFLATED) }

    init {
        for (config in configs) jobs.register("import:${config.id}", "Import ${config.id}", Duration.ofSeconds(config.refreshInterval)) { requestRefresh(config.id) }
    }

    suspend fun initialize() {
        val stored = store.snapshots()
        val configured = configs.mapTo(HashSet()) { it.id }
        published.set(
            Published(
                stored.filter { it.providerId in configured }.sortedBy { snapshot -> configs.indexOfFirst { it.id == snapshot.providerId } },
                store.offeringProtocols(),
            ),
        )
        // With no provider configured nothing tells live data from retired data, so nothing is pruned.
        if (configured.isEmpty()) return
        val cutoff = clock.instant().minus(RETIRED_AFTER)
        val retired = stored.filter { snapshot ->
            snapshot.providerId !in configured && runCatching { java.time.Instant.parse(snapshot.importedAt) }.getOrNull()?.isBefore(cutoff) == true
        }.map { it.providerId }
        if (retired.isNotEmpty()) {
            store.deleteSnapshots(retired)
            log.info("deleted retired provider snapshots providers={}", retired.sorted())
        }
        val kept = stored.filter { it.providerId !in retired }.flatMapTo(HashSet()) { snapshot -> snapshot.offerings.map { it.id } }
        val pruned = store.pruneOfferings(kept, cutoff)
        if (pruned > 0) log.info("deleted health of retired sources count={} retiredDays={}", pruned, RETIRED_AFTER.toDays())
    }

    /** Records the protocol a probe or media fetch found in the stream body; every later offering view carries it. */
    suspend fun recordProtocol(
        offeringId: String,
        protocol: String,
    ) {
        mutex.withLock {
            val current = published.get()
            if (current.protocols[offeringId] == protocol) return
            store.recordProtocol(offeringId, protocol, clock.instant())
            published.set(Published(current.feeds, current.protocols + (offeringId to protocol)))
            log.info("source protocol offering={} protocol={}", offeringId, protocol)
        }
    }

    fun providers(): List<ProviderStatus> = statuses.get()

    fun offering(id: String): Offering? = published.get().offeringById[id]

    fun offerings(): List<Offering> = published.get().offerings

    fun offeringsOf(channelId: String): List<Offering> = published.get().offeringsByChannel[channelId].orEmpty()

    fun channels(providerId: String? = null): List<Channel> {
        val current = published.get()
        return if (providerId == null) current.channels.map { it.first } else current.channels.filter { it.second == providerId }.map { it.first }
    }

    fun channel(id: String): Channel? = published.get().channelById[id]

    fun providerOf(channelId: String): String? = published.get().providerByChannel[channelId]

    fun config(providerId: String): FeedConfig = configs.first { it.id == providerId }

    /**
     * What to fetch for [offering]: its URL with the provider's headers, or for a native `svc://` source the URL
     * its service client resolves now, with that client's headers. Throws [ProviderFailure] when the service cannot answer.
     */
    suspend fun playable(offering: Offering): ResolvedStream =
        if (NativeServices.isNative(offering.url)) {
            checkNotNull(providers) { "Native source without a registry" }.resolve(offering)
        } else {
            ResolvedStream(offering.url, config(offering.providerId).headers)
        }

    /** Changes when any channel or source is added, changed or withdrawn. */
    fun revision(): String {
        val current = published.get()
        return "${current.channelsRevision}:${current.offeringsRevision}"
    }

    suspend fun refresh(config: FeedConfig) =
        importLocks.computeIfAbsent(config.id) { Mutex() }.withLock { jobs.record("import:${config.id}") {
            val started = clock.instant()
            try {
                val parsed = if (config.native) {
                    checkNotNull(providers) { "Native providers are configured without a registry" }.channels(config)
                } else {
                    val playlist = fetch(checkNotNull(config.playlistUrl), config.headers, 16 * 1024 * 1024)
                    FeedParser.playlist(config.id, playlist, config.defaultProtocol, config.renewable, config.playlistUrl)
                }
                val (channels, offerings) = parsed
                require(channels.isNotEmpty() || config.allowEmpty) { "Empty playlist requires explicit allowEmpty" }
                val succeededAt = clock.instant().toString()
                val firstSinceStart = statuses.get().firstOrNull { it.id == config.id }?.lastSuccessAt == null
                // Only publishing takes the shared lock, so a slow provider holds up neither another's import nor a protocol record.
                val unchanged = mutex.withLock {
                    val previous = published.get().feeds.find { it.providerId == config.id }
                    val retainedChannels = (channels + previous?.channels.orEmpty()).distinctBy { it.id }
                    // An import that changes nothing rewrites nothing and wakes nothing downstream.
                    val unchanged = previous != null && previous.channels.toSet() == retainedChannels.toSet() && previous.offerings.toSet() == offerings.toSet()
                    if (!unchanged) {
                        val snapshot = FeedSnapshot(config.id, retainedChannels, offerings, importedAt = succeededAt)
                        store.saveSnapshot(snapshot)
                        val current = published.get()
                        published.set(
                            Published(
                                (current.feeds.filterNot { it.providerId == config.id } + snapshot).sortedBy { item ->
                                    configs.indexOfFirst { it.id == item.providerId }
                                },
                                current.protocols,
                            ),
                        )
                    }
                    unchanged
                }
                statuses.updateAndGet { all ->
                    all.map {
                        if (it.id == config.id) {
                            ProviderStatus(
                                config.id,
                                succeededAt,
                                channels = channels.size,
                                sources = offerings.size,
                                skippedEntries = parsed.skippedCount,
                                skipReasons = parsed.skipSummary,
                                notes = parsed.noteSummary,
                            )
                        } else {
                            it
                        }
                    }
                }
                val durationMs = Duration.between(started, clock.instant()).toMillis()
                log.info(
                    "import provider={} channels={} sources={} skipped={} unchanged={} durationMs={}",
                    config.id, channels.size, offerings.size, parsed.skippedCount, unchanged, durationMs,
                )
                if (parsed.skippedCount > 0) log.warn("import provider={} skipped malformed entries: {}", config.id, parsed.skipSummary)
                parsed.noteSummary?.let { log.info("import provider={} derived identities: {}", config.id, it) }
                // The first success since start still wakes a lineup run that waits for every provider's first import.
                if (!unchanged || firstSinceStart) onImported()
                JobOutcome(true, "${channels.size} channels, ${offerings.size} sources" + if (unchanged) ", unchanged" else "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statuses.updateAndGet { all -> all.map { if (it.id == config.id) it.copy(errorCode = "IMPORT_FAILED") else it } }
                // The message, with its URLs redacted, tells an auth or rate limit from an outage.
                log.warn(
                    "import provider={} failed class={} transient={} message=\"{}\" durationMs={}",
                    config.id, e.javaClass.simpleName, (e as? ProviderFailure)?.transient ?: true, redactedMessage(e), Duration.between(started, clock.instant()).toMillis(),
                )
                JobOutcome(false, "IMPORT_FAILED (${e.javaClass.simpleName})")
            }
        } }

    /** Asks the provider's loop to import soon; repeated requests before it runs coalesce into one. */
    fun requestRefresh(providerId: String) {
        triggers[providerId]?.trySend(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // select { onTimeout }
    fun start(scope: CoroutineScope): List<Job> =
        configs.map { config ->
            scope.launch(CoroutineName("feed-refresh:${config.id}")) {
                val trigger = triggers.getValue(config.id)
                while (isActive) {
                    refresh(config)
                    select<Unit> {
                        trigger.onReceive {}
                        onTimeout(config.refreshInterval * 1000) {}
                    }
                }
            }
        }

    suspend fun sources(): List<SourceStatus> {
        val current = offerings()
        val states = store.allHealth()
        return current.map { offering ->
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

    suspend fun updateSource(
        id: String,
        update: UpdateSource,
    ): SourceStatus {
        val source = offering(id) ?: throw ApiFailure(404, "SOURCE_NOT_FOUND", "Unknown source")
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

    private suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        limit: Int,
    ): String =
        http.withUpstreamResponse(url, url, headers, policy = upstream) { response ->
            require(response.status.value == 200) { "Feed fetch failed" }
            val bytes = response.bodyAsChannel().readBuffer(limit.toLong() + 1).readByteArray()
            require(bytes.size <= limit) { "Feed exceeds size limit" }
            bytes.toString(Charsets.UTF_8)
        }
}

fun hash(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
