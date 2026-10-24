package dev.jamesrich.sofaking.backend

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for code that suspends: a [CancellationException] is rethrown, so canceling the caller still cancels
 * it, and an [Error] is never caught. It is the one catch-all in the server, for the places a failure of any kind is
 * logged or recorded and the work goes on: a background loop's next pass, a provider's last good answer.
 */
@Suppress("TooGenericExceptionCaught") // Catching every exception is what this is for; each caller says why.
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
