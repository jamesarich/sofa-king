package dev.jamesrich.sofaking.ui.player

/** When the tune in flight began, so the first rendered frame after it can be timed; with none in flight it is not. */
internal class TuneMark(private val now: () -> Long) {
    private var startedAtMs: Long? = null

    fun start() {
        startedAtMs = now()
    }

    /** Forgets the tune: it failed or was canceled, so no frame answers it. */
    fun clear() {
        startedAtMs = null
    }

    /** Milliseconds since [start], once; null when no tune is in flight. */
    fun take(): Long? = startedAtMs?.let { now() - it }.also { startedAtMs = null }
}
