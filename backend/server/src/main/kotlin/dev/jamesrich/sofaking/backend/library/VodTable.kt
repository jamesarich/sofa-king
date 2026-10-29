package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.playback.HistoryReason
import dev.jamesrich.sofaking.backend.playback.SessionHistory
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

    /** Where each session's start and end is recorded; none until the runtime sets it. */
    @Volatile var history: SessionHistory? = null

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

    /** Adds [state] to the sessions and reports its start. */
    fun add(state: VodState) {
        sessions[state.id] = state
        history?.started(state.start())
    }

    /**
     * Takes [state] out of the sessions for [reason], ending it in the history, and signals its FFmpeg; [dispose]
     * finishes it off the lock. Its id is never reused, so its folder is its own.
     */
    fun retire(state: VodState, reason: String) {
        if (sessions.remove(state.id) != null) history?.ended(state.start(), reason)
        state.process?.destroy()
        retired += state
    }

    /**
     * Whether a session of [intent] may start converting: [others] conversions run elsewhere. A watch that finds no
     * room ends the previews' conversions, the oldest first, one by one until there is, as a live watch ends a preview;
     * a preview never ends anything.
     */
    fun makeRoom(max: Int, others: Int, intent: String): Boolean {
        fun converting() = sessions.values.count { heavy(it.mode, it.media) } + others
        fun oldestPreview() = sessions.values.filter { it.intent == Wire.Intent.PREVIEW && heavy(it.mode, it.media) }
            .minByOrNull { it.startedAt }
        while (converting() >= max && intent == Wire.Intent.WATCH) {
            retire(oldestPreview() ?: break, HistoryReason.EVICTED)
        }
        return converting() < max
    }

    /** The open session [id], once the expired ones are gone. */
    fun live(id: String): VodState {
        reap()
        return sessions[id]
            ?: throw ApiFailure(HttpStatusCode.Gone, Wire.ErrorCode.SESSION_EXPIRED, "Session was released or expired")
    }

    fun reap() {
        val now = clock.instant()
        sessions.values.filter { it.expires <= now }.forEach { retire(it, HistoryReason.EXPIRED) }
    }

    /** Launches [state]'s FFmpeg, then retires it at once when the session was released or expired while it started. */
    suspend fun launchOwned(state: VodState) {
        runner.launch(state)
        locked { if (sessions[state.id] !== state) retire(state, HistoryReason.RELEASED) }
    }
}
