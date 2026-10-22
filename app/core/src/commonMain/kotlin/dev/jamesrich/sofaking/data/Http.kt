package dev.jamesrich.sofaking.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import kotlinx.io.IOException

/** The platform's HTTP engine: OkHttp on Android and the JVM, Darwin on Apple, curl on Linux, fetch on the web. */
internal expect val httpEngine: HttpClientEngineFactory<*>

/** True where the engine reports a failed request as a Throwable that is not an Exception: a browser's failed fetch. */
internal expect val engineFailsWithErrors: Boolean

/** The app's one connection pool. Every client shares it and none closes it, so it lives as long as the process. */
internal val sharedHttpEngine: HttpClientEngine by lazy { httpEngine.create() }

/**
 * A client on [engine] with [configure] applied. Cancellation closes the call's socket mid-body; the timeouts keep a
 * server that went quiet from holding a tune or a guide load forever. A transport failure the engine throws as
 * something other than an Exception becomes an [IOException], so `catch (e: Exception)` and retries see it. Closing
 * the client leaves [engine] open.
 */
internal fun httpClient(engine: HttpClientEngine = sharedHttpEngine, configure: HttpClientConfig<*>.() -> Unit = {}): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 20_000
            requestTimeoutMillis = 30_000
        }
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, _ ->
                if (engineFailsWithErrors && cause !is Exception) throw IOException(cause.message ?: cause.toString(), cause)
            }
        }
        configure()
    }

/** The client for calls to no server in particular, such as the update check; it is never closed. */
internal val apiHttpClient: HttpClient by lazy { httpClient() }

/** The same pool for channel logos and program images, which go out without the token. */
val mediaHttpClient: HttpClient get() = apiHttpClient
