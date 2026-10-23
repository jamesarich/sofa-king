package dev.jamesrich.sofaking.backend

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Checks [done] every [everyMillis] until it holds; past [timeoutMillis] the wait fails with a timeout. */
suspend fun pollUntil(timeoutMillis: Long, everyMillis: Long = 20, done: suspend () -> Boolean) {
    withTimeout(timeoutMillis) { while (!done()) delay(everyMillis) }
}

/** Reads [value] every [everyMillis] until it is not null and returns it; past [timeoutMillis] the wait fails with a timeout. */
suspend fun <T : Any> pollFor(timeoutMillis: Long, everyMillis: Long = 20, value: suspend () -> T?): T =
    withTimeout(timeoutMillis) {
        var current = value()
        while (current == null) {
            delay(everyMillis)
            current = value()
        }
        current
    }
