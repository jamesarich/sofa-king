package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue

/** The VOD sessions playing, which [mutex] guards, and the ending of those that stop. */
internal class VodTable(private val clock: Clock, private val runner: VodRunner) {
    val mutex = Mutex()
    val sessions = HashMap<String, VodState>()

    /** Sessions taken out under the lock whose FFmpeg and files [dispose] deals with once it is released. */
    val retired = ConcurrentLinkedQueue<VodState>()

    /**
     * Runs [action] under the sessions' lock, then ends the FFmpeg of every session it retired and deletes that
     * session's files, so a slow exit or a folder of thousands of segments never holds up the other requests.
     */
    suspend inline fun <T> locked(action: () -> T): T = try {
        mutex.withLock(action = action)
    } finally {
        dispose()
    }

    suspend fun dispose() {
        if (retired.isEmpty()) return
        withContext(NonCancellable) { while (true) runner.stop(retired.poll() ?: break) }
    }

    /**
     * Takes [state] out of the sessions and signals its FFmpeg; [dispose] finishes it off the lock. Its id is never
     * reused, so its folder is its own.
     */
    fun retire(state: VodState) {
        sessions.remove(state.id)
        state.process?.destroy()
        retired += state
    }

    /** The open session [id], once the expired ones are gone. */
    fun live(id: String): VodState {
        reap()
        return sessions[id]
            ?: throw ApiFailure(HttpStatusCode.Gone, Wire.ErrorCode.SESSION_EXPIRED, "Session was released or expired")
    }

    fun reap() {
        val now = clock.instant()
        sessions.values.filter { it.expires <= now }.forEach(::retire)
    }

    /** Launches [state]'s FFmpeg, then retires it at once when the session was released or expired while it started. */
    suspend fun launchOwned(state: VodState) {
        runner.launch(state)
        locked { if (sessions[state.id] !== state) retire(state) }
    }
}
