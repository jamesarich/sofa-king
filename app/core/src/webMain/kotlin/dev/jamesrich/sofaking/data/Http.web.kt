package dev.jamesrich.sofaking.data

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

internal actual val httpEngine: HttpClientEngineFactory<*> = Js
