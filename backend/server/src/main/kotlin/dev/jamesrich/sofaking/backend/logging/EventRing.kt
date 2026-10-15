package dev.jamesrich.sofaking.backend.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.time.Instant

/** One log line as the portal's Events page shows it: the message as it was written, with the request id when there was one. */
class LogEvent(val at: Instant, val level: String, val logger: String, val message: String, val requestId: String? = null)

/**
 * The newest [capacity] log events at INFO and above, in memory, for the portal's Events page. The same lines go to
 * stderr; nothing here outlives a restart.
 */
class EventRing(val capacity: Int = 500) {
    private val lock = Any()
    private val ring = ArrayDeque<LogEvent>(capacity)

    fun append(event: LogEvent) {
        synchronized(lock) {
            if (ring.size == capacity) ring.removeFirst()
            ring.addLast(event)
        }
    }

    /** The kept events, newest first. */
    fun snapshot(): List<LogEvent> = synchronized(lock) { ring.toList() }.asReversed()

    fun clear() = synchronized(lock) { ring.clear() }

    companion object {
        /** The ring every logger context appends to; one per process, since logback configures itself before anything is wired. */
        val shared = EventRing()
    }
}

/** Appends every event at INFO and above to [ring]; an exception adds its class to the line, never its message or trace. */
class RingAppender(private val ring: EventRing) : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        if (!event.level.isGreaterOrEqual(Level.INFO)) return
        val thrown = event.throwableProxy?.className?.substringAfterLast('.')
        val message = if (thrown == null) event.formattedMessage else "${event.formattedMessage} ($thrown)"
        ring.append(LogEvent(Instant.ofEpochMilli(event.timeStamp), event.level.toString(), event.loggerName, message, event.mdcPropertyMap?.get("requestId")))
    }
}
