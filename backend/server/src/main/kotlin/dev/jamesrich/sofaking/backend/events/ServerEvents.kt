package dev.jamesrich.sofaking.backend.events

import dev.jamesrich.sofaking.backend.api.ServerEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import java.time.Clock

/** The event types `GET /api/v1/admin/events/stream` sends. */
object EventType {
    const val SESSION_STARTED = "session.started"
    const val SESSION_ENDED = "session.ended"
    const val RECORDING_STATE = "recording.state"
    const val ATTENTION_CHANGED = "attention.changed"
}

/** How many events a slow listener may fall behind before it misses the oldest. */
private const val BUFFERED_EVENTS = 256

/**
 * What happens on the server as it happens, for the event stream and whatever else listens: sessions starting and
 * ending, recordings changing state, and attention items coming and going. Nothing is kept; a listener sees what is
 * published while it listens, and one that falls [BUFFERED_EVENTS] behind loses the oldest, never holding up a
 * publisher.
 */
class ServerEvents(private val clock: Clock) {
    private val flow = MutableSharedFlow<ServerEvent>(
        extraBufferCapacity = BUFFERED_EVENTS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<ServerEvent> = flow.asSharedFlow()

    /** Publishes an event of [type]; it never suspends, so it may be called under any lock. */
    fun publish(type: String, data: JsonObject) {
        flow.tryEmit(ServerEvent(type, clock.instant().toString(), data))
    }
}
