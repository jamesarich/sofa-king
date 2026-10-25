package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.ParsedPlaylist
import dev.jamesrich.sofaking.backend.catalog.stableId
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.deleteProviderState
import dev.jamesrich.sofaking.backend.store.providerState
import dev.jamesrich.sofaking.backend.store.saveProviderState
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.contract.Channel
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
            deviceId ?: (
                store.providerState(providerId, DEVICE_ID)?.value ?: UUID.randomUUID().toString().also {
                    store.saveProviderState(providerId, DEVICE_ID, it, null, clock.instant())
                }
                ).also { deviceId = it }
        }
    }

    /** A stored value that has not expired, or null. */
    suspend fun state(key: String): String? = store.providerState(providerId, key)?.takeIf {
        it.expiresAt == null || it.expiresAt > clock.instant()
    }?.value

    suspend fun saveState(key: String, value: String, expiresAt: Instant?) =
        store.saveProviderState(providerId, key, value, expiresAt, clock.instant())

    suspend fun clearState(key: String) = store.deleteProviderState(providerId, key)

    /**
     * [fresh] when it loaded, kept under [key]; when it failed, what the last load kept, across restarts too, or an
     * empty map before any load. For data whose loss costs more than its staleness, such as a channel's group.
     */
    suspend fun lastGood(key: String, fresh: Result<Map<String, String>>): Map<String, String> = fresh.fold(
        onSuccess = { value ->
            value.also {
                if (state(key) != stateJson.encodeToString(MAP, it)) {
                    saveState(key, stateJson.encodeToString(MAP, it), null)
                }
            }
        },
        onFailure = { state(key)?.let { runCatching { stateJson.decodeFromString(MAP, it) }.getOrNull() }.orEmpty() },
    )

    /** The value [fetch] produced within [ttl], else a fresh one; concurrent callers share one fetch. */
    suspend fun <T : Any> cached(key: String, ttl: Duration, fetch: suspend () -> T): T =
        fresh(key, ttl) ?: cacheLocks.computeIfAbsent(key) { Mutex() }.withLock {
            fresh(key, ttl) ?: fetch().also { caches[key] = clock.instant() to it }
        }

    /** The value cached under [key], while it is younger than [ttl]. */
    private fun <T : Any> fresh(key: String, ttl: Duration): T? {
        val (at, value) = caches[key] ?: return null
        @Suppress("UNCHECKED_CAST")
        return if (Duration.between(at, clock.instant()) < ttl) value as T else null
    }

    fun invalidate(key: String) {
        caches.remove(key)
    }

    companion object {
        const val DEVICE_ID = "device_id"
        private val MAP = MapSerializer(String.serializer(), String.serializer())
        private val stateJson = Json
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

    /**
     * The provider's channels as a parsed playlist: ids derived like an M3U channel's, every offering behind
     * `svc://` with the client's protocol.
     */
    suspend fun channels(config: FeedConfig): ParsedPlaylist {
        val client =
            clients[config.id] ?: throw ProviderFailure("No client for provider ${config.id}", transient = false)
        val listed = client.channels()
        val channels = LinkedHashMap<String, Channel>()
        val offerings = ArrayList<Offering>()
        var duplicates = 0
        for (item in listed.filter { it.id.isNotBlank() && it.name.isNotBlank() }) {
            val id = stableId("channel:${config.id}:${item.id}")
            if (channels.containsKey(id)) {
                duplicates++
            } else {
                val url = NativeServices.offeringUrl(client.service, item.id)
                channels[id] = Channel(
                    id,
                    item.number?.takeIf { it.isNotBlank() } ?: id.take(FALLBACK_NUMBER_LENGTH),
                    item.name.trim(),
                    httpOrNull(item.logo),
                    listOfNotNull(item.group?.takeIf { it.isNotBlank() }) + item.tags,
                    item.id,
                )
                offerings +=
                    Offering(stableId("offering:${config.id}:${item.id}:$url"), id, config.id, url, client.protocol)
            }
        }
        require(channels.size <= CHANNEL_LIMIT) { "Provider exceeds import limits" }
        return ParsedPlaylist(
            channels.values.toList(),
            offerings,
            if (duplicates > 0) {
                mapOf("duplicate-source" to duplicates)
            } else {
                emptyMap()
            },
        )
    }

    /** The playable URL behind a native offering, from the cache while it has at least a minute left. */
    suspend fun resolve(offering: Offering): ResolvedStream = resolved(offering.id)
        ?: locks.computeIfAbsent(offering.id) { Mutex() }.withLock { resolved(offering.id) ?: resolveNow(offering) }

    /** The cached resolution of offering [id] while it is still good. */
    private fun resolved(id: String): ResolvedStream? = resolutions[id]?.takeIf { it.until > clock.instant() }?.stream

    private suspend fun resolveNow(offering: Offering): ResolvedStream {
        val client =
            clients[offering.providerId]
                ?: throw ProviderFailure("No client for provider ${offering.providerId}", transient = false)
        val stream = try {
            client.resolve(NativeServices.channelId(offering.url))
        } catch (e: ProviderFailure) {
            log.warn(
                "resolve provider={} offering={} failed transient={} reason=\"{}\"",
                offering.providerId,
                offering.id,
                e.transient,
                e.message,
            )
            throw e
        }
        require(stream.url.startsWith("https://") || stream.url.startsWith("http://")) {
            "Resolved URL is not HTTP"
        }
        val until = minOf(stream.expiresAt?.minus(EXPIRY_MARGIN) ?: Instant.MAX, clock.instant().plus(MAX_CACHE))
        resolutions[offering.id] = Resolution(stream, until)
        return stream
    }

    companion object {
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(60)
        val MAX_CACHE: Duration = Duration.ofHours(6)

        fun create(
            configs: List<FeedConfig>,
            http: HttpClient,
            store: BackendStore,
            clock: Clock,
            policy: UpstreamPolicy,
            /** Clients the server builds from its own parts, by service, such as `virtual`. */
            local: Map<String, (ProviderContext) -> ProviderClient> = emptyMap(),
        ): ProviderRegistry {
            val transport = ProviderHttp(http, policy)
            val clients = configs.filter { it.native }.associate { config ->
                val context = ProviderContext(config.id, config.options, transport, clock, store)
                val service = requireNotNull(config.service)
                config.id to (local[service]?.invoke(context) ?: client(service, context))
            }
            return ProviderRegistry(clients, clock)
        }

        fun client(service: String, context: ProviderContext): ProviderClient = when (service) {
            "whale" -> WhaleClient(context)
            "vizio" -> VizioClient(context)
            "lg" -> LgClient(context)
            "freelivesports" -> FreeLiveSportsClient(context)
            "hallmark" -> HallmarkClient(context)
            "ballysports" -> BallySportsClient(context)
            "adultswim" -> AdultSwimClient(context)
            "pbs" -> PbsClient(context)
            "samsung" -> SamsungClient(context)
            "plex" -> PlexClient(context)
            "stirr" -> StirrClient(context)
            "tcl" -> TclClient(context)
            "roku" -> RokuClient(context)
            "localnow" -> LocalNowClient(context)
            "xumo" -> XumoClient(context)
            "pluto" -> PlutoClient(context)
            "tubi" -> TubiClient(context)
            "hdhomerun" -> HdHomeRunClient(context)
            "direct" -> DirectClient(context)
            else -> throw IllegalArgumentException("Unknown native service $service")
        }
    }
}

/** A channel with no number of its own is numbered by the start of its ID. */
private const val FALLBACK_NUMBER_LENGTH = 8

private const val CHANNEL_LIMIT = 10_000
