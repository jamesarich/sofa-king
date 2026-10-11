package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.api.Channel
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.ParsedPlaylist
import dev.jamesrich.sofaking.backend.catalog.stableId
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.deleteProviderState
import dev.jamesrich.sofaking.backend.store.providerState
import dev.jamesrich.sofaking.backend.store.saveProviderState
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * What one provider's client is built on: its configured options, HTTP, the clock, and durable state in
 * SQLite (one random device id per provider per install, session tokens). [cached] shares one fetch of a
 * service payload between the channel list, the guide and resolves that follow each other.
 */
class ProviderContext(
    val providerId: String,
    val options: Map<String, String>,
    val http: ProviderHttp,
    val clock: Clock,
    private val store: BackendStore,
) {
    private val caches = ConcurrentHashMap<String, Pair<Instant, Any>>()
    private val cacheLocks = ConcurrentHashMap<String, Mutex>()
    private val stateLock = Mutex()
    @Volatile private var deviceId: String? = null

    /** A UUID made once for this provider on this install and kept in SQLite; it stands in for an ad identifier. */
    suspend fun deviceId(): String {
        deviceId?.let { return it }
        return stateLock.withLock {
            deviceId ?: (store.providerState(providerId, DEVICE_ID)?.value ?: UUID.randomUUID().toString().also {
                store.saveProviderState(providerId, DEVICE_ID, it, null, clock.instant())
            }).also { deviceId = it }
        }
    }

    /** A stored value that has not expired, or null. */
    suspend fun state(key: String): String? = store.providerState(providerId, key)?.takeIf { it.expiresAt == null || it.expiresAt > clock.instant() }?.value

    suspend fun saveState(key: String, value: String, expiresAt: Instant?) = store.saveProviderState(providerId, key, value, expiresAt, clock.instant())

    suspend fun clearState(key: String) = store.deleteProviderState(providerId, key)

    /** The value [fetch] produced within [ttl], else a fresh one; concurrent callers share one fetch. */
    suspend fun <T : Any> cached(key: String, ttl: Duration, fetch: suspend () -> T): T {
        caches[key]?.let { (at, value) -> if (Duration.between(at, clock.instant()) < ttl) @Suppress("UNCHECKED_CAST") return value as T }
        return cacheLocks.computeIfAbsent(key) { Mutex() }.withLock {
            caches[key]?.let { (at, value) -> if (Duration.between(at, clock.instant()) < ttl) @Suppress("UNCHECKED_CAST") return value as T }
            fetch().also { caches[key] = clock.instant() to it }
        }
    }

    fun invalidate(key: String) {
        caches.remove(key)
    }

    companion object {
        const val DEVICE_ID = "device_id"
    }
}

/**
 * The configured native providers: builds each service client, turns its channel list into ordinary channels
 * and offerings whose URL is `svc://service/id`, and resolves those URLs for the relay, the probes and the
 * remux. Resolutions are cached per offering until just before they expire; one provider failing is that
 * provider's import or playback failing and nothing else.
 */
class ProviderRegistry(private val clients: Map<String, ProviderClient>, private val clock: Clock) {
    private class Resolution(val stream: ResolvedStream, val until: Instant)

    private val log = LoggerFactory.getLogger(ProviderRegistry::class.java)
    private val resolutions = ConcurrentHashMap<String, Resolution>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun client(providerId: String): ProviderClient? = clients[providerId]

    /** The provider's channels as a parsed playlist: ids derived like an M3U channel's, every offering HLS behind `svc://`. */
    suspend fun channels(config: FeedConfig): ParsedPlaylist {
        val client = clients[config.id] ?: throw ProviderFailure("No client for provider ${config.id}", transient = false)
        val listed = client.channels()
        val channels = LinkedHashMap<String, Channel>()
        val offerings = ArrayList<Offering>()
        var duplicates = 0
        for (item in listed) {
            if (item.id.isBlank() || item.name.isBlank()) continue
            val id = stableId("channel:${config.id}:${item.id}")
            if (channels.containsKey(id)) { duplicates++; continue }
            val url = NativeServices.offeringUrl(client.service, item.id)
            channels[id] = Channel(
                id, item.number?.takeIf { it.isNotBlank() } ?: id.take(8), item.name.trim(), httpOrNull(item.logo),
                listOfNotNull(item.group?.takeIf { it.isNotBlank() }) + item.tags, item.id,
            )
            offerings += Offering(stableId("offering:${config.id}:${item.id}:$url"), id, config.id, url, "hls")
        }
        require(channels.size <= 10_000) { "Provider exceeds import limits" }
        return ParsedPlaylist(channels.values.toList(), offerings, if (duplicates > 0) mapOf("duplicate-source" to duplicates) else emptyMap())
    }

    /** The playable URL behind a native offering, from the cache while it has at least a minute left. */
    suspend fun resolve(offering: Offering): ResolvedStream {
        val now = clock.instant()
        resolutions[offering.id]?.takeIf { it.until > now }?.let { return it.stream }
        return locks.computeIfAbsent(offering.id) { Mutex() }.withLock {
            resolutions[offering.id]?.takeIf { it.until > clock.instant() }?.let { return it.stream }
            val client = clients[offering.providerId] ?: throw ProviderFailure("No client for provider ${offering.providerId}", transient = false)
            val stream = try {
                client.resolve(NativeServices.channelId(offering.url))
            } catch (e: ProviderFailure) {
                log.warn("resolve provider={} offering={} failed transient={} reason=\"{}\"", offering.providerId, offering.id, e.transient, e.message)
                throw e
            }
            require(stream.url.startsWith("https://") || stream.url.startsWith("http://")) { "Resolved URL is not HTTP" }
            val until = minOf(stream.expiresAt?.minus(EXPIRY_MARGIN) ?: Instant.MAX, clock.instant().plus(MAX_CACHE))
            resolutions[offering.id] = Resolution(stream, until)
            stream
        }
    }

    /** Drops a cached resolution, for a caller that found the URL dead before its expiry. */
    fun forget(offeringId: String) {
        resolutions.remove(offeringId)
    }

    companion object {
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(60)
        val MAX_CACHE: Duration = Duration.ofHours(6)

        fun create(configs: List<FeedConfig>, http: HttpClient, store: BackendStore, clock: Clock, policy: UpstreamPolicy): ProviderRegistry {
            val transport = ProviderHttp(http, policy)
            val clients = configs.filter { it.native }.associate { config ->
                val context = ProviderContext(config.id, config.options, transport, clock, store)
                config.id to client(requireNotNull(config.service), context)
            }
            return ProviderRegistry(clients, clock)
        }

        fun client(service: String, context: ProviderContext): ProviderClient = when (service) {
            "whale" -> WhaleClient(context)
            "vizio" -> VizioClient(context)
            "lg" -> LgClient(context)
            else -> throw IllegalArgumentException("Unknown native service $service")
        }
    }
}
