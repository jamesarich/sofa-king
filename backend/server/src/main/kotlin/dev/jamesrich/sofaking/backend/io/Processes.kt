package dev.jamesrich.sofaking.backend.io

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the process, outside the caller's cancellation so a cancel cannot drop it once forked, and returns it.
 * Forking blocks, so it runs on [Dispatchers.IO].
 */
internal suspend fun ProcessBuilder.startOwned(): Process = withContext(NonCancellable + Dispatchers.IO) { start() }

/**
 * Runs the process to its exit and returns the exit code, or null when it ran past [timeout]. The process never
 * outlives the call: a timeout or a cancel terminates it.
 */
internal suspend fun ProcessBuilder.runFor(timeout: Duration): Int? {
    val process = startOwned()
    try {
        return withTimeoutOrNull(timeout) { process.onExit().await() }?.exitValue()
    } finally {
        process.terminate()
    }
}

/** Asks the process to end, kills it when it has not within [grace], and waits for the exit without blocking a thread. */
internal suspend fun Process.terminate(grace: Duration = 2.seconds) {
    if (!isAlive) return
    withContext(NonCancellable) {
        destroy()
        if (withTimeoutOrNull(grace) { onExit().await() } == null) {
            destroyForcibly()
            withTimeoutOrNull(grace) { onExit().await() }
        }
    }
}
