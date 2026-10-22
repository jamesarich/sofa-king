package dev.jamesrich.sofaking.data

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl

internal actual val httpEngine: HttpClientEngineFactory<*> = Curl

internal actual val engineFailsWithErrors: Boolean = false
