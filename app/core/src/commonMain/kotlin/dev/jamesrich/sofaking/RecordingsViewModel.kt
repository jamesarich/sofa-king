package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.RecordingSpace
import dev.jamesrich.sofaking.data.SeriesRule
import dev.jamesrich.sofaking.data.SofaKingApi
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.UpdateRecordingDto
import dev.jamesrich.sofaking.data.UpdateRecordingRuleDto
import dev.jamesrich.sofaking.data.describe
import dev.jamesrich.sofaking.data.failedLoad
import dev.jamesrich.sofaking.data.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

sealed interface RecordingsState {
    data object Loading : RecordingsState

    /** No usable server address yet. */
    data object Unconfigured : RecordingsState

    /** The server does not record, or is too old to. */
    data object Unavailable : RecordingsState

    /** Every recording but cancelled ones, soonest scheduled first, then the rest newest first, and the series rules, oldest first. */
    data class Ready(val recordings: List<DvrRecording>, val space: RecordingSpace, val rules: List<SeriesRule> = emptyList()) : RecordingsState
    data class Failed(val message: String) : RecordingsState
}

/**
 * The server's recordings for the Recordings screen and their playback through the app's one [sessions].
 * [apis] is the API client for the server Settings names, null while there is none. [changed] runs after a
 * recording is deleted, so the guide's marks follow.
 */
class RecordingsViewModel(
    apis: Flow<SofaKingApi?>,
    private val sessions: PlaybackSessions,
    private val changed: suspend () -> Unit = {},
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** How often a playing recording's position is saved on the server. */
    private val saveInterval: Duration = 10.seconds,
) : ViewModel() {
    private var api: SofaKingApi? = null

    private val _state = MutableStateFlow<RecordingsState>(RecordingsState.Loading)
    val state: StateFlow<RecordingsState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private val positions = PositionSaver(timeSource, saveInterval)

    init {
        viewModelScope.launch {
            apis.collect { found ->
                api = found
                loadJob?.cancel()
                if (found == null) _state.value = RecordingsState.Unconfigured else refresh()
            }
        }
    }

    /** Reads the recordings again, keeping the list on screen until the new one is in. */
    fun refresh() {
        val api = api ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (_state.value !is RecordingsState.Ready) _state.value = RecordingsState.Loading
            _state.value = try {
                val list = api.recordings()
                val rules = api.recordingRules().rules.map { it.toModel() }
                RecordingsState.Ready(list.recordings.mapNotNull { it.toModel() }, RecordingSpace(list.storage.freeBytes, list.storage.totalBytes), rules)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SofaKingException) {
                if (e.status == 404) RecordingsState.Unavailable else failed(e)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    private fun failed(e: Exception): RecordingsState =
        failedLoad("recordings", e, _state.value as? RecordingsState.Ready, RecordingsState::Failed)

    /** [id] as the list has it, or null before the list is in or once it is gone. */
    fun recording(id: String): DvrRecording? = (_state.value as? RecordingsState.Ready)?.recordings?.firstOrNull { it.id == id }

    /**
     * Cancels a scheduled recording, stops a running one, or deletes a finished one and its file. False when the
     * server refused or could not be reached; the list reloads either way.
     */
    suspend fun delete(recording: DvrRecording): Boolean {
        val api = api ?: return false
        val done = try {
            api.deleteRecording(recording.id)
            _state.update { state ->
                if (state is RecordingsState.Ready) state.copy(recordings = state.recordings.filterNot { it.id == recording.id }) else state
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "deleting a recording failed: ${e.describe()}" }
            false
        }
        refresh()
        changed()
        return done
    }

    /**
     * Changes [rule]: whether it takes new episodes only, and how many recordings it keeps (0 for all). False when the
     * server refused or could not be reached; the list reloads either way.
     */
    suspend fun updateRule(rule: SeriesRule, newOnly: Boolean = rule.newOnly, keep: Int = rule.keep): Boolean =
        changeRule("changing a series rule") { api ->
            val fresh = api.updateRecordingRule(rule.id, UpdateRecordingRuleDto(newOnly = newOnly, keep = keep)).toModel()
            _state.update { state -> if (state is RecordingsState.Ready) state.copy(rules = state.rules.map { if (it.id == fresh.id) fresh else it }) else state }
        }

    /** Deletes [rule] and the recordings it scheduled; what it recorded stays. */
    suspend fun deleteRule(rule: SeriesRule): Boolean = changeRule("deleting a series rule") { api ->
        api.deleteRecordingRule(rule.id)
        _state.update { state -> if (state is RecordingsState.Ready) state.copy(rules = state.rules.filterNot { it.id == rule.id }) else state }
    }

    private suspend fun changeRule(what: String, action: suspend (SofaKingApi) -> Unit): Boolean {
        val api = api ?: return false
        val done = try {
            action(api)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "$what failed: ${e.describe()}" }
            false
        }
        refresh()
        changed()
        return done
    }

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
    fun progress(recordingId: String, positionMs: Long, durationMs: Long?) {
        sessions.reportPosition(positionMs)
        if (positions.due(recordingId)) save(recordingId, UpdateRecordingDto(positionMs = positionMs.coerceAtLeast(0)), refresh = false)
    }

    /** Playback of [recordingId] stopped at [positionMs]: saved now, watched near the end, and the list reloads. */
    fun stopped(recordingId: String, positionMs: Long, durationMs: Long?) {
        sessions.reportPosition(positionMs)
        positions.reset(recordingId)
        val watched = durationMs != null && durationMs > 0 && positionMs >= durationMs * WATCHED_AT
        val update = if (watched) UpdateRecordingDto(positionMs = 0, watched = true) else UpdateRecordingDto(positionMs = positionMs.coerceAtLeast(0))
        save(recordingId, update, refresh = true)
    }

    private fun save(recordingId: String, update: UpdateRecordingDto, refresh: Boolean) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val fresh = api.updateRecording(recordingId, update).toModel()
                if (refresh && fresh != null) {
                    _state.update { state ->
                        if (state is RecordingsState.Ready) state.copy(recordings = state.recordings.map { if (it.id == fresh.id) fresh else it }) else state
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "saving the recording position failed: ${e.describe()}" }
            }
        }
    }

    private companion object {
        val log = Logger.withTag("SofaKing")

        /** Stopped this far in, a recording counts as watched, as the server counts library items. */
        const val WATCHED_AT = 0.90
    }
}
