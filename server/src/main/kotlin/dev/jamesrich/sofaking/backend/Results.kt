package dev.jamesrich.sofaking.backend

import kotlinx.coroutines.CancellationException

/** [runCatching] for code that suspends: a [CancellationException] is rethrown, so cancelling the caller still cancels it. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
