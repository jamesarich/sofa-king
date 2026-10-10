package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.Channel
import dev.jamesrich.sofaking.backend.api.ProviderStatus
import dev.jamesrich.sofaking.backend.api.SourceStatus
import dev.jamesrich.sofaking.backend.api.UpdateSource
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class ApiFailure(
    val status: Int,
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
) : RuntimeException(message)

/** Provider playlists: the candidate channels and their sources. The lineup decides what the app sees. */
class FeedService(
    val configs: List<FeedConfig>,
    private val store: BackendStore,
    private val http: HttpClient,
    private val clock: Clock,
    /** Governs every upstream fetch made for these feeds: playlists, guides, probes and relayed media. */
    val upstream: UpstreamPolicy = UpstreamPolicy(),
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
        val offeringBySourceKey = offerings.groupBy { sourceKey(it.url) }
        val channelsRevision = hash(Json.encodeToString(channels.map { it.first }))

        /** Which sources exist decides playbackEligible, and a withdrawal writes no health row. */
        val offeringsRevision = hash(offerings.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.channelId}" })
    }

    private val log = LoggerFactory.getLogger(FeedService::class.java)
    private val published = AtomicReference(Published(emptyList()))
    private val statuses = AtomicReference<List<ProviderStatus>>(configs.map { ProviderStatus(it.id) })
    private val mutex = Mutex()

    suspend fun initialize() {
        published.set(
            Published(
                store.snapshots().filter { snapshot -> configs.any { it.id == snapshot.providerId } }.sortedBy { snapshot ->
                    configs.indexOfFirst { it.id == snapshot.providerId }
                },
                store.offeringProtocols(),
            ),
        )
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

    /** Offerings whose URL matches [url] ignoring its query string. */
    fun offeringsMatching(url: String): List<Offering> = sourceKey(url)?.let { published.get().offeringBySourceKey[it] }.orEmpty()

    fun channels(providerId: String? = null): List<Channel> {
        val current = published.get()
        return if (providerId == null) current.channels.map { it.first } else current.channels.filter { it.second == providerId }.map { it.first }
    }

    fun channel(id: String): Channel? = published.get().channelById[id]

    fun providerOf(channelId: String): String? = published.get().providerByChannel[channelId]

    fun config(providerId: String): FeedConfig = configs.first { it.id == providerId }

    /** Changes when any channel or source is added, changed or withdrawn. */
    fun revision(): String {
        val current = published.get()
        return "${current.channelsRevision}:${current.offeringsRevision}"
    }

    suspend fun refresh(config: FeedConfig) =
        mutex.withLock {
            val started = clock.instant()
            try {
                val playlist = fetch(config.playlistUrl, config.headers, 16 * 1024 * 1024)
                val parsed = FeedParser.playlist(config.id, playlist, config.defaultProtocol)
                val (channels, offerings) = parsed
                require(channels.isNotEmpty() || config.allowEmpty) { "Empty playlist requires explicit allowEmpty" }
                val previous = published.get().feeds.find { it.providerId == config.id }
                val retainedChannels = (channels + previous?.channels.orEmpty()).distinctBy { it.id }
                val snapshot = FeedSnapshot(config.id, retainedChannels, offerings, importedAt = clock.instant().toString())
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
                statuses.set(
                    statuses.get().map {
                        if (it.id == config.id) {
                            ProviderStatus(
                                config.id,
                                snapshot.importedAt,
                                channels = channels.size,
                                sources = offerings.size,
                                skippedEntries = parsed.skippedCount,
                                skipReasons = parsed.skipSummary,
                            )
                        } else {
                            it
                        }
                    },
                )
                val durationMs = Duration.between(started, clock.instant()).toMillis()
                log.info(
                    "import provider={} channels={} sources={} skipped={} durationMs={}",
                    config.id, channels.size, offerings.size, parsed.skippedCount, durationMs,
                )
                if (parsed.skippedCount > 0) log.warn("import provider={} skipped malformed entries: {}", config.id, parsed.skipSummary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Exception messages can carry signed URLs and credentials; log the class only.
                statuses.set(statuses.get().map { if (it.id == config.id) it.copy(errorCode = "IMPORT_FAILED") else it })
                log.warn("import provider={} failed class={} durationMs={}", config.id, e.javaClass.simpleName, Duration.between(started, clock.instant()).toMillis())
            }
        }

    fun start(scope: CoroutineScope): List<Job> =
        configs.map { config ->
            scope.launch {
                while (isActive) {
                    refresh(config)
                    delay(config.refreshSeconds * 1000)
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

fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
