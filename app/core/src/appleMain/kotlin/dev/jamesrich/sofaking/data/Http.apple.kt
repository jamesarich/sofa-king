package dev.jamesrich.sofaking.data

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual val httpEngine: HttpClientEngineFactory<*> = Darwin

internal actual val engineFailsWithErrors: Boolean = false
