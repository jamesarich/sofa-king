package dev.jamesrich.sofaking.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout

/** The platform's HTTP engine: OkHttp on Android and the JVM, Darwin on Apple, curl on Linux, fetch on the web. */
internal expect val httpEngine: HttpClientEngineFactory<*>

/**
 * The app's one connection pool for API calls. Cancellation closes the call's socket mid-body; the timeouts
 * keep a server that went quiet from holding a tune or a guide load forever.
 */
internal val apiHttpClient: HttpClient by lazy {
    HttpClient(httpEngine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 20_000
            requestTimeoutMillis = 30_000
        }
    }
}

/** The same pool for channel logos and program images, which go out without the token. */
val mediaHttpClient: HttpClient get() = apiHttpClient
