package dev.jamesrich.sofaking.ui

/**
 * Counts the screens keeping the display awake, for platforms where that is one process-wide switch: [acquire] runs
 * for the first holder and [release] once the last lets go, so a page that leaves while the next one plays (Up next
 * cross-fades the two) does not let the display sleep. Used from the main thread.
 */
internal class WakeHolds(private val acquire: () -> Unit, private val release: () -> Unit) {
    private var holders = 0

    /** Holds the display awake until the returned handle is closed; closing it again does nothing. */
    fun hold(): AutoCloseable {
        if (holders++ == 0) acquire()
        var open = true
        return AutoCloseable {
            if (open) {
                open = false
                if (--holders == 0) release()
            }
        }
    }
}
