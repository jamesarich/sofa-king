package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.admin.sameSecret
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.http.clientAbort
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.backend.upstream.RELAY_CREDENTIAL_HEADER
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.UpstreamRejectedException
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The relay FFmpeg reads every upstream through: the HDHomeRun remux, the recorder and the probes' decode tier. Each
 * FFmpeg run holds a lease: a loopback URL for its source and a random credential it sends as a header. A lease's
 * requests are served by [relayResource], the code that serves players: every hop is fetched through the address
 * policy with the source's headers scoped to its origin, and every playlist is rewritten so its variants, segments and
 * keys come back to the lease too. FFmpeg so connects nowhere but here, and never holds the provider's headers.
 *
 * The listener binds 127.0.0.1 on a port of its own, started with the first lease, so its one route is no part of the
 * public routes or the API, and nothing off this machine reaches it. An unknown lease or a
 * wrong credential answers 401.
 */
class LoopbackRelay(
    private val http: HttpClient,
    private val policy: UpstreamPolicy,
    /** Shared with [MediaRelay], so FFmpeg's upstream requests count toward the same cap as players'. */
    private val permits: Semaphore = Semaphore(UPSTREAM_REQUESTS),
) {
    private val log = LoggerFactory.getLogger(LoopbackRelay::class.java)
    private val leases = ConcurrentHashMap<String, Lease>()
    private val starting = Mutex()

    @Volatile private var server: EmbeddedServer<*, *>? = null

    @Volatile private var port = 0

    @Volatile private var closed = false

    /** One FFmpeg run's source and the resources its playlists named, by opaque token. */
    internal class Lease(val stream: ResolvedStream, val stalls: StallWatch?) {
        val id: String = UUID.randomUUID().toString()
        val credential: String = ByteArray(32).also(random::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        private val resources = ResourceMap()

        @Synchronized fun root(protocol: String): String = resourceToken(
            stream.url,
            if (protocol == "hls") {
                "m3u8"
            } else {
                "ts"
            },
        ).also { resources.put(it, stream.url, pinned = true) }

        @Synchronized fun path(url: String): String = "/relay/$id/" +
            (
                resources.tokenFor(url)
                    ?: resourceToken(url).also { resources.put(it, url, pinned = it.endsWith(".m3u8")) }
                )

        @Synchronized fun url(token: String): String? = resources[token]
    }

    /** Leases open now, one per FFmpeg reading a source. */
    fun active(): Int = leases.size

    /**
     * Runs [block] with a lease on [stream], whose [protocol] names the root's suffix; the lease ends with the block,
     * and any request FFmpeg makes after that answers 401. Given [stalls], each playlist FFmpeg fetches is shown to it.
     */
    suspend fun <T> reading(
        stream: ResolvedStream,
        protocol: String,
        stalls: StallWatch? = null,
        block: suspend (FfmpegSource) -> T,
    ): T {
        val port = start()
        val lease = Lease(stream, stalls)
        leases[lease.id] = lease
        try {
            return block(
                FfmpegSource("http://127.0.0.1:$port/relay/${lease.id}/${lease.root(protocol)}", lease.credential),
            )
        } finally {
            leases.remove(lease.id)
        }
    }

    private suspend fun start(): Int {
        server?.let { return port }
        return starting.withLock {
            server?.let { return@withLock port }
            check(!closed) { "The relay is closed" }
            withContext(Dispatchers.IO) {
                val engine = embeddedServer(
                    Netty,
                    environment = applicationEnvironment { log = this@LoopbackRelay.log },
                    configure = {
                        connector {
                            host = "127.0.0.1"
                            port = 0
                        }
                    },
                ) { module() }
                // Ktor's own shutdown hook may stop the listener before the runtime closes; no lease starts it again.
                engine.monitor.subscribe(ApplicationStopped) {
                    closed = true
                    server = null
                }
                engine.start(wait = false)
                port = engine.engine.resolvedConnectors().first().port
                server = engine
                port
            }
        }
    }

    private fun Application.module() {
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                when {
                    cause is CancellationException -> throw cause

                    cause is ApiFailure -> call.respond(cause.status)

                    // A 4xx other than 401, 403 and 429, so a probe counts a source that points at a
                    // refused address against the source.
                    cause is UpstreamRejectedException -> call.respond(HttpStatusCode.UnprocessableEntity)

                    clientAbort(cause) -> {}

                    else -> {
                        // Exception messages can carry upstream URLs; only the class is logged.
                        log.warn("relay request failed class={}", cause.javaClass.simpleName)
                        call.respond(HttpStatusCode.BadGateway)
                    }
                }
            }
        }
        routing {
            get("/relay/{lease}/{token}") {
                val lease = leases[call.parameters["lease"]]
                if (lease == null || !sameSecret(call.request.headers[RELAY_CREDENTIAL_HEADER], lease.credential)) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@get
                }
                val url =
                    lease.url(call.parameters["token"]!!) ?: run {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                permits.withPermit {
                    // FFmpeg's own messages name the status, which the probe's failure classes
                    // read; so it gets the upstream's.
                    relayResource(
                        http,
                        policy,
                        call,
                        url,
                        lease.stream,
                        path = lease::path,
                        manifest = { text, source -> text.also { lease.stalls?.stalled(it, source) } },
                        refused = { status ->
                            val answer = if (status in UPSTREAM_ERRORS) {
                                HttpStatusCode.fromValue(status)
                            } else {
                                HttpStatusCode.BadGateway
                            }
                            throw ApiFailure(answer, "UPSTREAM_STATUS", "Upstream answered $status")
                        },
                    )
                }
            }
        }
    }

    /**
     * Stops the listener, unless Ktor's shutdown hook already has; the server is shutting down,
     * after every FFmpeg has been killed.
     */
    fun close() {
        closed = true
        server?.let { running ->
            server = null
            // The hook can be stopping it at the same moment; a second stop has nothing left to do.
            runCatching { running.stop(0, STOP_TIMEOUT_MS) }
        }
        leases.clear()
    }

    private companion object {
        val random = SecureRandom()
    }
}

/** The upstream's own error statuses, passed on as they are; anything else is a bad gateway. */
private val UPSTREAM_ERRORS = 400..599

/** How many upstream requests the relays make at once, FFmpeg's and players' together. */
internal const val UPSTREAM_REQUESTS = 32

/** How long a stopping relay waits for its open responses. */
private const val STOP_TIMEOUT_MS = 1_000L
