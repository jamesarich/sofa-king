package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.notify.NotifyEvent
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.recordingRules
import dev.jamesrich.sofaking.backend.store.recordings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/** Publishes [recording]'s new state as a `recording.state` event, when there is anywhere to publish it. */
internal fun ServerEvents?.recordingChanged(recording: StoredRecording) {
    this?.publish(EventType.RECORDING_STATE, recording.eventData())
}

/**
 * Publishes one `recording.culled` for a cleanup pass that deleted [removed]: the first recording's fields and its
 * reason, the `count` deleted and the `titles` of the first [CULLED_TITLES], so a long history never sends a push each.
 */
internal fun ServerEvents?.recordingsCulled(removed: List<Pair<StoredRecording, String>>) {
    val (first, reason) = removed.firstOrNull() ?: return
    this?.publish(
        NotifyEvent.RECORDING_CULLED,
        buildJsonObject {
            first.eventData().forEach { (key, value) -> put(key, value) }
            put("reason", reason)
            put("count", removed.size)
            putJsonArray("titles") { removed.take(CULLED_TITLES).forEach { add(it.first.title) } }
        },
    )
}

private const val CULLED_TITLES = 3

/** Publishes `recording.preempted`: a recording on [forChannelId] took the slot of a session watching [channelId]. */
internal fun ServerEvents?.recordingPreempted(
    channelId: String,
    channelName: String?,
    forChannelId: String,
    forChannelName: String?,
) {
    this?.publish(
        NotifyEvent.RECORDING_PREEMPTED,
        buildJsonObject {
            put("channelId", channelId)
            put("channelName", channelName)
            put("forChannelId", forChannelId)
            put("forChannelName", forChannelName)
        },
    )
}

private fun StoredRecording.eventData(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("episodeTitle", episodeTitle)
    put("channelId", channelId)
    put("channelName", channelName)
    put("state", state)
    put("reason", reason)
    put("bytes", bytes)
}

/**
 * The DVR's events beyond a recording's state: a recording that newly conflicts for a slot, and the recordings disk
 * dropping under its free-space floor. Each is published once as it happens, not again while it lasts; the first
 * conflict check after a start only learns what conflicts already.
 */
internal class DvrAlerts(
    private val store: BackendStore,
    private val events: ServerEvents?,
    /** How many recordings may run at once. */
    private val capacity: () -> Int,
) {
    private val conflicted = HashSet<String>()
    private var learned = false
    private var low = false

    /** How many recordings conflict as of the last [checkConflicts]. */
    @Synchronized
    fun conflictCount(): Int = conflicted.size

    /** Publishes `recording.conflict` for each scheduled or running recording that did not conflict before. */
    suspend fun checkConflicts() {
        val recordings = store.recordings().filter { it.state != CANCELED }
        val priorities = store.recordingRules().associate { it.id to it.priority }
        conflicts(recordings, DvrPlanner.conflicts(recordings, priorities, capacity()))
    }

    @Synchronized
    private fun conflicts(recordings: List<StoredRecording>, conflicts: Set<String>) {
        val fresh = conflicts - conflicted
        conflicted.retainAll(conflicts)
        conflicted += conflicts
        if (!learned) {
            learned = true
            return
        }
        for (recording in recordings.filter { it.id in fresh }) {
            events?.publish(EventType.RECORDING_CONFLICT, recording.eventData())
        }
    }

    /** Publishes `disk.low` when [free] bytes drop under [floor], once until they are back over it. */
    @Synchronized
    fun space(free: Long?, floor: Long) {
        if (free == null || floor <= 0) return
        val now = free < floor
        if (now && !low) {
            events?.publish(
                EventType.DISK_LOW,
                buildJsonObject {
                    put("freeBytes", free)
                    put("floorBytes", floor)
                    put("freeGib", "%.1f".format(Locale.US, free.toDouble() / Dvr.GIB))
                    put("floorGib", floor / Dvr.GIB)
                },
            )
        }
        low = now
    }
}
