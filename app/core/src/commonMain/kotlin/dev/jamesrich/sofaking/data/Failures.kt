package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

private val log = Logger.withTag("SofaKing")

/** The server's own error code when it answered, else the transport's message; never a URL or token. */
internal fun Throwable.describe(): String = when (this) {
    is SofaKingException -> "$code: $message"
    else -> message ?: toString()
}

/**
 * The state a screen shows after its load failed: [loaded], the loaded state already on screen, stays; with nothing
 * loaded yet it is [failed] with why. The failure is logged as [what].
 */
internal fun <S> failedLoad(what: String, failure: Throwable, loaded: S?, failed: (String) -> S): S {
    log.w { "$what failed: ${failure.describe()}" }
    return loaded ?: failed(failure.describe())
}

/**
 * Logs a coroutine failure nothing else handles, such as a settings write the store refused, rather than letting it
 * crash the process. It applies to coroutines launched under a supervisor, as `viewModelScope`
 * and the connection's scope are.
 */
internal val logFailures = CoroutineExceptionHandler { _, e -> log.e(e) { "unhandled failure: ${e.describe()}" } }

/**
 * [runCatching] for code that suspends: a [CancellationException] is rethrown, so canceling the caller still cancels
 * it, and an [Error] is never caught. It is the app's one catch-all, for the places where a failure of any kind, a
 * server that cannot be reached or a platform's own exception, is logged or shown and the screen goes on.
 */
@Suppress("TooGenericExceptionCaught") // Catching every exception is what this is for; each caller says why.
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
