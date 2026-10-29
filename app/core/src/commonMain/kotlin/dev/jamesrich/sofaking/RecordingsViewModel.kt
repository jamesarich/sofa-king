package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.RecordingSchedule
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.data.SeriesRule
import dev.jamesrich.sofaking.data.UpdateRecordingDto
import dev.jamesrich.sofaking.data.setProtected
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The server's recordings for the Recordings screen, from the connection's [schedule], which the guide's marks read
 * too, and their playback through the app's one [sessions].
 */
class RecordingsViewModel(
    private val schedule: RecordingSchedule,
    private val sessions: PlaybackSessions,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** How often a playing recording's position is saved on the server. */
    private val saveInterval: Duration = 10.seconds,
) : ViewModel() {
    val state: StateFlow<RecordingsState> = schedule.state

    private val positions = PositionSaver(timeSource, saveInterval)

    /** Reads the recordings again, keeping the list on screen until the new one is in. */
    fun refresh() {
        viewModelScope.launch { schedule.refresh() }
    }

    /** [id] as the list has it, or null before the list is in or once it is gone. */
    fun recording(id: String): DvrRecording? = (state.value as? RecordingsState.Ready)?.recordings?.firstOrNull {
        it.id == id
    }

    /**
     * Cancels a scheduled recording, stops a running one, or deletes a finished one and its file. False when the
     * server refused or could not be reached; the list reloads either way.
     */
    suspend fun delete(recording: DvrRecording): Boolean = schedule.delete(recording)

    /** Has cleanup keep [recording] or delete it when space runs short. False when the server refused. */
    suspend fun setProtected(recording: DvrRecording, protect: Boolean): Boolean =
        schedule.setProtected(recording, protect)

    /**
     * Changes [rule]: whether it takes new episodes only, and how many recordings it keeps (0 for all). False when the
     * server refused or could not be reached; the list reloads either way.
     */
    suspend fun updateRule(rule: SeriesRule, newOnly: Boolean = rule.newOnly, keep: Int = rule.keep): Boolean =
        schedule.updateRule(rule, newOnly, keep)

    /** Deletes [rule] and the recordings it scheduled; what it recorded stays. */
    suspend fun deleteRule(rule: SeriesRule): Boolean = schedule.deleteRule(rule)

    /**
     * Opens [recording] on the app's one session, releasing whatever was held: from the start with [fromStart],
     * else where the household stopped in it. [profile] is what the player plays as it is.
     */
    suspend fun play(recording: DvrRecording, profile: PlaybackProfile, fromStart: Boolean = false): PlaybackStream {
        val start = recording.positionMs.takeIf { !fromStart && recording.inProgress } ?: 0
        positions.reset(recording.id)
        return sessions.playRecording(recording.id, profile, start)
    }

    /** The player is at [positionMs] in [recordingId]: remembered for a reopen, and saved every [saveInterval]. */
    fun progress(recordingId: String, positionMs: Long) {
        sessions.reportPosition(positionMs)
        if (positions.due(recordingId)) {
            save(recordingId, UpdateRecordingDto(positionMs = positionMs.coerceAtLeast(0)), refresh = false)
        }
    }

    /** Playback of [recordingId] stopped at [positionMs]: saved now, watched near the end, and the list reloads. */
    fun stopped(recordingId: String, positionMs: Long, durationMs: Long?) {
        sessions.reportPosition(positionMs)
        positions.reset(recordingId)
        val watched = durationMs != null && durationMs > 0 && positionMs >= durationMs * WATCHED_AT
        val update = if (watched) {
            UpdateRecordingDto(
                positionMs = 0,
                watched = true,
            )
        } else {
            UpdateRecordingDto(positionMs = positionMs.coerceAtLeast(0))
        }
        save(recordingId, update, refresh = true)
    }

    private fun save(recordingId: String, update: UpdateRecordingDto, refresh: Boolean) {
        viewModelScope.launch { schedule.save(recordingId, update, show = refresh) }
    }

    private companion object {
        /** Stopped this far in, a recording counts as watched, as the server counts library items. */
        const val WATCHED_AT = 0.90
    }
}
