package dev.jamesrich.sofaking.backend.library

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** Runs one job per key at a time and shares its result with the callers that arrive meanwhile. */
internal class SingleFlight<T : Any> {
    private val running = ConcurrentHashMap<String, CompletableDeferred<T?>>()

    /** Runs [make] for [key] unless a caller already is; a slow job blocks no other key. */
    suspend fun once(key: String, make: suspend () -> T?): T? {
        val mine = CompletableDeferred<T?>()
        running.putIfAbsent(key, mine)?.let { return it.await() }
        try {
            return make().also { mine.complete(it) }
        } finally {
            mine.complete(null)
            running.remove(key, mine)
        }
    }
}
