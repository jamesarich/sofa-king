package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.Catalog
import dev.jamesrich.sofaking.backend.api.ChannelGuide
import dev.jamesrich.sofaking.backend.api.GuideQuery
import dev.jamesrich.sofaking.backend.api.GuideWindow
import dev.jamesrich.sofaking.backend.api.ProviderStatus
import dev.jamesrich.sofaking.backend.api.SourceStatus
import dev.jamesrich.sofaking.backend.api.UpdateSource
import dev.jamesrich.sofaking.backend.health.HealthStatus
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
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class ApiFailure(
    val status: Int,
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
) : RuntimeException(message)

class FeedService(
    val configs: List<FeedConfig>,
    private val store: BackendStore,
    private val http: HttpClient,
    private val clock: Clock,
    /** Governs every upstream fetch made for these feeds: playlists, guides, probes and relayed media. */
    val upstream: UpstreamPolicy = UpstreamPolicy(),
) {
    private class Published(
        val feeds: List<FeedSnapshot>,
    ) {
        val channels = feeds.flatMap { it.channels }.distinctBy { it.id }
        val offerings = feeds.flatMap { it.offerings }
        val offeringById = offerings.associateBy { it.id }
        val programmes = feeds.flatMap { it.programmes }.distinctBy { it.id }
        val programmesByChannel = programmes.groupBy { it.channelId }
        val guideRevision = hash(Json.encodeToString(programmes))
        val channelsRevision = hash(Json.encodeToString(channels))

        /** When each channel's guide runs out; the only time-dependent input to the catalog body. */
        val guideEnds = programmesByChannel.mapValues { (_, rows) -> rows.maxOf { Instant.parse(it.endsAt) } }
        val guideEndsSorted = guideEnds.entries.sortedBy { it.key }.map { it.key to it.value }
        val guideCoverage = hash(guideEndsSorted.joinToString(",") { it.first })
    }

    private val log = LoggerFactory.getLogger(FeedService::class.java)
    private val published = AtomicReference(Published(emptyList()))
    private val statuses = AtomicReference<List<ProviderStatus>>(configs.map { ProviderStatus(it.id) })
    private val mutex = Mutex()

    suspend fun initialize() {
        published.set(
            Published(
                store.snapshots().filter { snapshot -> configs.any { it.id == snapshot.providerId } }.sortedBy { snapshot ->
                    configs.indexOfFirst {
                        it.id ==
                            snapshot.providerId
                    }
                },
            ),
        )
    }

    fun providers(): List<ProviderStatus> = statuses.get()

    fun offering(id: String): Offering? = published.get().offeringById[id]

    fun offerings(): List<Offering> = published.get().offerings

    fun config(providerId: String): FeedConfig = configs.first { it.id == providerId }

    suspend fun refresh(config: FeedConfig) =
        mutex.withLock {
            val started = clock.instant()
            try {
                val playlist = fetch(config.playlistUrl, config.headers, 16 * 1024 * 1024)
                val parsed = FeedParser.playlist(config.id, playlist, config.defaultProtocol)
                val (channels, offerings) = parsed
                require(channels.isNotEmpty() || config.allowEmpty) { "Empty playlist requires explicit allowEmpty" }
                val programmes = config.guideUrl?.let { FeedParser.guide(fetch(it, config.headers, 64 * 1024 * 1024), channels) }.orEmpty()
                val previous = published.get().feeds.find { it.providerId == config.id }
                val retainedChannels = (channels + previous?.channels.orEmpty()).distinctBy { it.id }
                val snapshot = FeedSnapshot(config.id, retainedChannels, offerings, programmes, clock.instant().toString())
                store.saveSnapshot(snapshot)
                published.set(
                    Published(
                        (published.get().feeds.filterNot { it.providerId == config.id } + snapshot).sortedBy { item ->
                            configs.indexOfFirst {
                                it.id ==
                                    item.providerId
                            }
                        },
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
                    "import provider={} channels={} sources={} programmes={} skipped={} durationMs={}",
                    config.id, channels.size, offerings.size, programmes.size, parsed.skippedCount, durationMs,
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

    /** Snapshot, guide coverage, health version and which channels' guides are still current: everything the body depends on. */
    suspend fun catalogRevision(): String {
        val current = published.get()
        val now = clock.instant()
        val available = buildString { for ((id, end) in current.guideEndsSorted) if (end > now) append(id).append(',') }
        return hash("${current.channelsRevision}:${current.guideCoverage}:${store.healthVersion()}:${hash(available)}")
    }

    suspend fun catalog(revision: String? = null): Catalog {
        val stamped = revision ?: catalogRevision()
        val current = published.get()
        val states = store.allHealth()
        val guideEnds = current.guideEnds
        val offers = current.offerings.groupBy { it.channelId }
        val now = clock.instant()
        val channels =
            current.channels
                .map { channel ->
                    val end = guideEnds[channel.id]
                    val guide =
                        if (channel.legacyGuideId == null) {
                            "unassigned"
                        } else if (end == null) {
                            "empty"
                        } else if (end > now) {
                            "available"
                        } else {
                            "stale"
                        }
                    channel.copy(
                        guideStatus = guide,
                        playbackEligible =
                            offers[channel.id].orEmpty().any { offering ->
                                states[offering.id]?.let {
                                    !it.manuallyDisabled &&
                                        it.status in setOf(HealthStatus.UNVERIFIED, HealthStatus.HEALTHY, HealthStatus.SUSPECT)
                                } ==
                                    true
                            },
                    )
                }.sortedWith(compareBy({ it.displayNumber.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.name }, { it.id }))
        return Catalog(stamped, channels)
    }

    fun guide(query: GuideQuery): GuideWindow {
        if (query.channelIds.size !in 1..200 || query.channelIds.distinct().size != query.channelIds.size) {
            throw ApiFailure(400, "INVALID_QUERY", "Request 1 to 200 distinct channel IDs")
        }
        val from =
            try {
                Instant.parse(query.from)
            } catch (
                _: Exception,
            ) {
                throw ApiFailure(400, "INVALID_QUERY", "Invalid from timestamp")
            }
        val to =
            try {
                Instant.parse(query.to)
            } catch (_: Exception) {
                throw ApiFailure(400, "INVALID_QUERY", "Invalid to timestamp")
            }
        if (to <= from ||
            Duration.between(from, to) > Duration.ofHours(24)
        ) {
            throw ApiFailure(400, "INVALID_QUERY", "Guide window must be positive and at most 24 hours")
        }
        val current = published.get()
        val channels = current.channels.associateBy { it.id }
        if (query.channelIds.any { it !in channels }) throw ApiFailure(404, "CHANNEL_NOT_FOUND", "Unknown channel ID")
        val revision = current.guideRevision
        if (query.revision != null && query.revision != revision) throw ApiFailure(409, "RESYNC_REQUIRED", "Guide revision changed", true)
        val grouped =
            query.channelIds.associateWith { id ->
                current.programmesByChannel[id].orEmpty().filter {
                    Instant.parse(it.startsAt) <
                        to &&
                        Instant.parse(it.endsAt) > from
                }
            }
        return GuideWindow(
            revision,
            from.toString(),
            to.toString(),
            query.channelIds.map { id ->
                val rows = grouped[id].orEmpty()
                ChannelGuide(
                    id,
                    if (channels[id]!!.legacyGuideId == null) {
                        "unassigned"
                    } else if (rows.isEmpty()) {
                        "empty"
                    } else {
                        "available"
                    },
                    rows,
                )
            },
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
