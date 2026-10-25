package dev.jamesrich.sofaking.backend.notify

import dev.jamesrich.sofaking.backend.api.ServerEvent
import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The events a notification target can take, as the API and the webhook's `eventType` name them. */
object NotifyEvent {
    const val RECORDING_SCHEDULED = "recording.scheduled"
    const val RECORDING_STARTED = "recording.started"
    const val RECORDING_FINISHED = "recording.finished"
    const val RECORDING_FAILED = "recording.failed"
    const val RECORDING_CONFLICT = EventType.RECORDING_CONFLICT
    const val SOURCE_QUARANTINED = EventType.SOURCE_QUARANTINED
    const val SOURCE_RECOVERED = EventType.SOURCE_RECOVERED
    const val DISK_LOW = EventType.DISK_LOW
    const val UPDATE_AVAILABLE = EventType.UPDATE_AVAILABLE
    const val LIBRARY_ADDED = EventType.LIBRARY_ADDED
    const val ATTENTION_RAISED = "attention.raised"
    const val ATTENTION_CLEARED = "attention.cleared"

    /** What the Test button sends; every target takes it. */
    const val TEST = "test"

    /** Every event a target may choose, in the Connect page's order, with its label there. */
    val LABELS: Map<String, String> = linkedMapOf(
        RECORDING_SCHEDULED to "A recording is scheduled",
        RECORDING_STARTED to "A recording starts",
        RECORDING_FINISHED to "A recording finishes",
        RECORDING_FAILED to "A recording fails or is missed",
        RECORDING_CONFLICT to "Recordings conflict for a tuner slot",
        SOURCE_QUARANTINED to "A source is quarantined",
        SOURCE_RECOVERED to "A quarantined source recovers",
        DISK_LOW to "The recordings disk runs low",
        UPDATE_AVAILABLE to "An update is published",
        LIBRARY_ADDED to "A scan adds library files",
        ATTENTION_RAISED to "An item needs attention on Home",
        ATTENTION_CLEARED to "An attention item clears",
    )

    val ALL: Set<String> = LABELS.keys
}

/** How a notification reads: ntfy and Apprise show these; the webhook carries them beside its data. */
enum class NotifyTone(val apprise: String, val ntfyTag: String) {
    INFO("info", "information_source"),
    SUCCESS("success", "white_check_mark"),
    WARNING("warning", "warning"),
    FAILURE("failure", "rotating_light"),
}

/**
 * One notification: [event] is a [NotifyEvent] name, [data] the server event's fields, and [path] the portal page
 * about it, when there is one.
 */
data class Notification(
    val event: String,
    val occurredAt: String,
    val title: String,
    val message: String,
    val tone: NotifyTone,
    val data: JsonObject,
    val path: String? = null,
)

/** The notifications [event] makes: none for a session, one for most, one per item for an attention change. */
internal fun notificationsOf(event: ServerEvent): List<Notification> {
    val data = event.data
    fun note(type: String, title: String, message: String, tone: NotifyTone, path: String? = null) =
        Notification(type, event.at, title, message, tone, data, path)
    return when (event.type) {
        EventType.RECORDING_STATE -> listOfNotNull(recordingNote(event))

        EventType.RECORDING_CONFLICT ->
            listOf(
                note(
                    NotifyEvent.RECORDING_CONFLICT,
                    "Recording conflict",
                    "${recordingName(data)} has no free slot at its time",
                    NotifyTone.WARNING,
                    "/admin/recordings",
                ),
            )

        EventType.SOURCE_QUARANTINED ->
            listOf(
                note(
                    NotifyEvent.SOURCE_QUARANTINED,
                    "Source quarantined",
                    "${sourceName(data)} failed its probes and is set aside",
                    NotifyTone.WARNING,
                    "/admin/providers",
                ),
            )

        EventType.SOURCE_RECOVERED ->
            listOf(
                note(
                    NotifyEvent.SOURCE_RECOVERED,
                    "Source recovered",
                    "${sourceName(data)} plays again",
                    NotifyTone.SUCCESS,
                    "/admin/providers",
                ),
            )

        EventType.DISK_LOW ->
            listOf(
                note(
                    NotifyEvent.DISK_LOW,
                    "Recordings disk low",
                    "${data.text("freeGib")} GiB free, under the ${data.text("floorGib")} GiB floor",
                    NotifyTone.FAILURE,
                    "/admin/recordings",
                ),
            )

        EventType.UPDATE_AVAILABLE ->
            listOf(
                note(
                    NotifyEvent.UPDATE_AVAILABLE,
                    "Update available",
                    "Build ${data.text("revision") ?: "unknown"} is published",
                    NotifyTone.INFO,
                    "/admin/system/about",
                ),
            )

        EventType.LIBRARY_ADDED -> listOf(libraryNote(event))

        EventType.ATTENTION_CHANGED -> attentionNotes(event)

        else -> emptyList()
    }
}

private fun recordingNote(event: ServerEvent): Notification? {
    val data = event.data
    val (type, title, tone) = when (data.text("state")) {
        Wire.RecordingState.SCHEDULED -> Triple(NotifyEvent.RECORDING_SCHEDULED, "Recording scheduled", NotifyTone.INFO)

        Wire.RecordingState.RECORDING -> Triple(NotifyEvent.RECORDING_STARTED, "Recording started", NotifyTone.INFO)

        Wire.RecordingState.COMPLETED ->
            Triple(NotifyEvent.RECORDING_FINISHED, "Recording finished", NotifyTone.SUCCESS)

        Wire.RecordingState.FAILED -> Triple(NotifyEvent.RECORDING_FAILED, "Recording failed", NotifyTone.FAILURE)

        else -> return null
    }
    val why = data.text("reason")?.takeIf { type == NotifyEvent.RECORDING_FAILED }?.let { " ($it)" }.orEmpty()
    return Notification(type, event.at, title, recordingName(data) + why, tone, data, "/admin/recordings")
}

private fun libraryNote(event: ServerEvent): Notification {
    val data = event.data
    val count = data.text("added")?.toIntOrNull() ?: 0
    val titles = (data["titles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val more = if (count > titles.size) ", and ${count - titles.size} more" else ""
    val library = data.text("library") ?: "a library"
    val message = if (titles.isEmpty()) "$count new in $library" else titles.joinToString() + more
    return Notification(
        NotifyEvent.LIBRARY_ADDED,
        event.at,
        "New in $library",
        message,
        NotifyTone.INFO,
        data,
        "/admin/library",
    )
}

private fun attentionNotes(event: ServerEvent): List<Notification> {
    fun items(name: String) = (event.data[name] as? JsonArray).orEmpty().map { it.jsonObject }
    val raised = items("raised").map { item ->
        val tone = if (item.text("tone") == "bad") NotifyTone.FAILURE else NotifyTone.WARNING
        val title = item.text("title").orEmpty()
        Notification(
            NotifyEvent.ATTENTION_RAISED,
            event.at,
            "Needs attention: $title",
            item.text("detail") ?: title,
            tone,
            item,
            item.text("href"),
        )
    }
    val cleared = items("cleared").map { item ->
        val title = item.text("title").orEmpty()
        Notification(
            NotifyEvent.ATTENTION_CLEARED,
            event.at,
            "Resolved: $title",
            title,
            NotifyTone.SUCCESS,
            item,
            item.text("href"),
        )
    }
    return raised + cleared
}

private fun recordingName(data: JsonObject): String {
    val name = listOfNotNull(data.text("title"), data.text("episodeTitle")).joinToString(" · ")
    return data.text("channelName")?.let { "$name on $it" } ?: name
}

private fun sourceName(data: JsonObject): String {
    val channel = data.text("channelName") ?: data.text("channelId") ?: "A source"
    return data.text("providerId")?.let { "$channel from $it" } ?: channel
}

/** [name]'s value as text, null when it is absent or JSON null. */
internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
