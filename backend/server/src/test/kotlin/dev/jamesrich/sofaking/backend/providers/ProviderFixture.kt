package dev.jamesrich.sofaking.backend.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One canned answer: a status, a body and a content type, or a redirect. */
class Canned(
    val status: Int = 200,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "application/json",
    val location: String? = null,
) {
    constructor(
        text: String,
        status: Int = 200,
        contentType: String = "application/json",
    ) : this(status, text.toByteArray(), contentType)
}

/**
 * A stand-in for a provider's API and CDN on loopback. Routes are matched on the request path; the handler sees
 * the exchange so a test can assert on headers and the query. Every request is counted by path.
 */
class ProviderFixture : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    val base = "http://127.0.0.1:${server.address.port}"
    val routes = ConcurrentHashMap<String, (HttpExchange) -> Canned>()
    val hits = ConcurrentHashMap<String, AtomicInteger>()

    /** The headers of the last request per path. */
    val lastHeaders = ConcurrentHashMap<String, Map<String, String>>()
    val lastQuery = ConcurrentHashMap<String, String>()
    val lastBody = ConcurrentHashMap<String, String>()

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            lastHeaders[path] =
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            exchange.requestURI.rawQuery?.let { lastQuery[path] = it }
            lastBody[path] = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val answer = routes[path]?.invoke(exchange) ?: Canned("not found", 404, "text/plain")
            answer.location?.let { exchange.responseHeaders.set("Location", it) }
            exchange.responseHeaders.set("Content-Type", answer.contentType)
            if (answer.body.isEmpty()) {
                exchange.sendResponseHeaders(answer.status, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(answer.status, answer.body.size.toLong())
                exchange.responseBody.use { it.write(answer.body) }
            }
        }
        server.start()
    }

    fun route(path: String, handler: (HttpExchange) -> Canned) {
        routes[path] = handler
    }

    fun json(path: String, body: String) = route(path) { Canned(body) }

    fun text(path: String, body: String, contentType: String = "text/plain") = route(path) {
        Canned(body, contentType = contentType)
    }

    fun hits(path: String): Int = hits[path]?.get() ?: 0

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun resourceBytes(name: String): ByteArray =
            ProviderFixture::class.java.classLoader.getResourceAsStream("providers/$name")!!.use {
                it.readBytes()
            }

        fun resource(name: String): String = resourceBytes(name).toString(Charsets.UTF_8)

        fun httpClient(): HttpClient = HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 5000
                socketTimeoutMillis =
                    10_000
            }
        }

        fun context(
            providerId: String,
            options: Map<String, String>,
            http: HttpClient,
            store: BackendStore,
            clock: MutableClock,
        ): ProviderContext =
            ProviderContext(providerId, options, ProviderHttp(http, UpstreamPolicy(allowLoopback = true)), clock, store)

        const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nmedia.m3u8\n"
    }
}

/** What a provider test runs against: the [fixture], a [http] client, a new [store] and a [clock]. */
class ProviderScope(
    val fixture: ProviderFixture,
    val http: HttpClient,
    val store: BackendStore,
    val clock: MutableClock,
) {
    /** The context provider [providerId] runs in, with its [options]. */
    fun context(providerId: String, options: Map<String, String> = emptyMap()): ProviderContext =
        ProviderFixture.context(providerId, options, http, store, clock)
}

/**
 * Runs [block] against a new fixture, client and store in a directory named from [prefix], on [clock], then closes
 * them and deletes the directory.
 */
fun providerTest(prefix: String, clock: MutableClock = MutableClock(), block: suspend ProviderScope.() -> Unit) =
    runBlocking<Unit> {
        val dir = Files.createTempDirectory(prefix)
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store -> ProviderScope(fixture, http, store, clock).block() }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
