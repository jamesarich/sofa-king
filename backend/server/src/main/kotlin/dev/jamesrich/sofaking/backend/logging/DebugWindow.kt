package dev.jamesrich.sofaking.backend.logging

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A spell of DEBUG logging that ends on its own: [open] logs at DEBUG to stderr and the log file for [length], then
 * the operator's level from [base] comes back. A level finer than DEBUG stays as it is.
 */
class DebugWindow(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val base: () -> String,
    private val apply: (String) -> Unit = ::applyLogLevel,
    val length: Duration = DEFAULT_LENGTH,
) {
    private val log = LoggerFactory.getLogger(DebugWindow::class.java)
    private var timer: Job? = null

    /** When the window closes; null while it is shut. */
    @Volatile var until: Instant? = null
        private set

    /** Logs at DEBUG for [length] from now, restarting the window when it is already open. */
    @Synchronized fun open() {
        timer?.cancel()
        until = clock.instant().plus(length)
        apply(level(base()))
        log.info("logging: debug for {} minutes", length.toMinutes())
        timer = scope.launch(CoroutineName("debug-window")) {
            delay(length.toMillis())
            close()
        }
    }

    /** Ends the window now and puts the operator's level back. */
    @Synchronized fun close() {
        if (until == null) return
        until = null
        timer?.cancel()
        timer = null
        apply(base())
        log.info("logging: debug window closed")
    }

    /** The level to log at for the operator's [level]: DEBUG while the window is open, unless [level] is finer. */
    fun level(level: String): String {
        if (until == null) return level
        val levels = LogSettings.LEVELS
        return if (levels.indexOf(level) in 0 until levels.indexOf(DEBUG)) level else DEBUG
    }

    companion object {
        val DEFAULT_LENGTH: Duration = Duration.ofMinutes(30)
        private const val DEBUG = "debug"
    }
}
