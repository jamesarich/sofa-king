package dev.jamesrich.sofaking.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.seconds

/** The platform's HTTP engine: OkHttp on Android and the JVM, Darwin on Apple, curl on Linux, fetch on the web. */
internal expect val httpEngine: HttpClientEngineFactory<*>

/** True where the engine reports a failed request as a Throwable that is not an Exception: a browser's failed fetch. */
internal expect val engineFailsWithErrors: Boolean

/** The app's one connection pool. Every client shares it and none closes it, so it lives as long as the process. */
internal val sharedHttpEngine: HttpClientEngine by lazy { httpEngine.create() }

/** How long a connection may take to open, a socket may sit idle, and a whole request may take. */
private val CONNECT_TIMEOUT = 5.seconds
private val SOCKET_TIMEOUT = 20.seconds
private val REQUEST_TIMEOUT = 30.seconds

/**
 * A client on [engine] with [configure] applied. Cancellation closes the call's socket mid-body; the timeouts keep a
 * server that went quiet from holding a tune or a guide load forever. A transport failure the engine throws as
 * something other than an Exception becomes an [IOException], so `catch (e: Exception)` and retries see it. Closing
 * the client leaves [engine] open.
 */
internal fun httpClient(
    engine: HttpClientEngine = sharedHttpEngine,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds
        socketTimeoutMillis = SOCKET_TIMEOUT.inWholeMilliseconds
        requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
    }
    HttpResponseValidator {
        handleResponseExceptionWithRequest { cause, _ ->
            if (engineFailsWithErrors && cause !is Exception) {
                throw IOException(cause.message ?: cause.toString(), cause)
            }
        }
    }
    configure()
}

/**
 * Loads Ktor's classes and finds its logger provider now. On the JVM a logger reads the APK to find its provider, which
 * stalls whichever thread makes the app's first request, and only a request or a log call does it: building a client
 * does not. Call this off the main thread.
 */
fun warmUpHttp() {
    httpClient().close()
    KtorSimpleLogger("dev.jamesrich.sofaking.warmup").trace("warm-up")
}

/** The client for calls to no server in particular, such as the update check; it is never closed. */
internal val apiHttpClient: HttpClient by lazy { httpClient() }

/** The same pool for channel logos and program images, which go out without the token. */
val mediaHttpClient: HttpClient get() = apiHttpClient
