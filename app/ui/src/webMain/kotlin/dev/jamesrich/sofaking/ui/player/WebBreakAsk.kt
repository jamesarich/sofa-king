package dev.jamesrich.sofaking.ui.player

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.LiveSessions
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ASK_MS = 1_000L
private const val RETRY_MS = 15_000L
private val log = Logger.withTag("SofaKing")

/**
 * The browser engine's ad break, asked of the server once a second with how far ahead the video element has loaded,
 * because a `<video>` element exposes no `EXT-X-DATERANGE` marks outside Safari. This goes away with the Compose web
 * engine. [ahead] reads the loaded-ahead time now, null when the element cannot say.
 */
internal class WebBreakAsk(private val ahead: () -> Long?) {
    private val scope = CoroutineScope(SupervisorJob())
    private var sessions: LiveSessions? = null

    /** The last answer; null outside a break, and while the ask has nothing to go on. */
    var last: AdBreakNow? = null
        private set

    init {
        scope.launch {
            while (true) {
                val held = sessions
                val loaded = ahead()
                if (held == null || loaded == null) {
                    last = null
                    delay(ASK_MS)
                    continue
                }
                val answer = runCatchingCancellable { held.adBreak(loaded) }
                last = answer.getOrNull()
                answer.exceptionOrNull()?.let { log.d { "ad break: ask failed: ${it.message}" } }
                // A server from before the break screen, or one between sessions: ask again in a while.
                delay(if (answer.isFailure) RETRY_MS else ASK_MS)
            }
        }
    }

    fun use(sessions: LiveSessions) {
        this.sessions = sessions
    }

    fun release() = scope.cancel()
}
