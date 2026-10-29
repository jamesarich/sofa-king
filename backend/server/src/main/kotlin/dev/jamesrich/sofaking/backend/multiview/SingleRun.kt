package dev.jamesrich.sofaking.backend.multiview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * One background run at a time. [replace] and [stop] take turns, and nothing starts after [stop], so a restart racing
 * a stop cannot leave a run that nobody owns.
 */
internal class SingleRun(private val scope: CoroutineScope, private val context: CoroutineContext) {
    private val lock = Mutex()
    private var job: Job? = null
    private var stopped = false

    /** Ends the current run, calls [prepare] for the next one's body and launches it; false when already stopped. */
    suspend fun replace(prepare: suspend () -> suspend () -> Unit): Boolean = lock.withLock {
        if (stopped) return false
        job?.cancelAndJoin()
        val body = prepare()
        job = scope.launch(context) { body() }
        true
    }

    /** Ends the current run, runs [after], and refuses every later [replace]. */
    suspend fun stop(after: suspend () -> Unit) = lock.withLock {
        stopped = true
        job?.cancelAndJoin()
        after()
    }
}
