package dev.jamesrich.sofaking.data

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual val httpEngine: HttpClientEngineFactory<*> = OkHttp
