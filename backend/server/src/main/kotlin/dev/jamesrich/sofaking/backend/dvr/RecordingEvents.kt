package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.backend.events.ServerEvents
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Publishes [recording]'s new state as a `recording.state` event, when there is anywhere to publish it. */
internal fun ServerEvents?.recordingChanged(recording: StoredRecording) {
    this?.publish(
        EventType.RECORDING_STATE,
        buildJsonObject {
            put("id", recording.id)
            put("title", recording.title)
            put("episodeTitle", recording.episodeTitle)
            put("channelId", recording.channelId)
            put("channelName", recording.channelName)
            put("state", recording.state)
            put("reason", recording.reason)
            put("bytes", recording.bytes)
        },
    )
}
