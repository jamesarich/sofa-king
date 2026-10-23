package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Instant

sealed interface RecordingsState {
    data object Loading : RecordingsState

    /** No usable server address yet. */
    data object Unconfigured : RecordingsState

    /** The server does not record, or is too old to. */
    data object Unavailable : RecordingsState

    /**
     * Every recording but cancelled ones, soonest scheduled first, then the rest newest first, and
     * the series rules, oldest first.
     */
    data class Ready(
        val recordings: List<DvrRecording>,
        val space: RecordingSpace,
        val rules: List<SeriesRule> = emptyList(),
    ) : RecordingsState
    data class Failed(val message: String) : RecordingsState
}

/** Whether a programme is set to record, on its own or through a series rule for its title. */
data class RecordingMark(val recording: Boolean = false, val series: Boolean = false)

/** The server's scheduled recordings and series rules, as the guide marks programmes with them. */
data class RecordingMarks(
    /** Scheduled and running recordings by [recordingKey], to their ids. */
    internal val recordings: Map<String, String> = emptyMap(),
    /** Series rules by [ruleKey], to their ids; a rule for any channel has no channel in its key. */
    internal val rules: Map<String, String> = emptyMap(),
) {
    fun of(programme: Programme) = RecordingMark(
        recording = recordingKey(programme.channelId, programme.start) in recordings,
        series = ruleId(programme) != null,
    )

    internal fun ruleId(programme: Programme): String? =
        rules[ruleKey(programme.title, programme.channelId)] ?: rules[ruleKey(programme.title, null)]
}

private fun recordingKey(channelId: String, start: Instant) = "$channelId@${start.toEpochMilliseconds()}"

// The server matches a rule's title ignoring case and outer spaces.
private fun ruleKey(title: String, channelId: String?) = title.trim().lowercase() + "@" + channelId.orEmpty()

private fun RecordingsState.Ready.marks() = RecordingMarks(
    recordings = recordings.filter { it.state == RecordingState.SCHEDULED || it.state == RecordingState.RECORDING }
        .associate { recordingKey(it.channelId, it.startsAt) to it.id },
    rules = rules.associate { ruleKey(it.title, it.channelId) to it.id },
)

/**
 * The server's recordings and series rules, read together once for both the Recordings tab ([state]) and the guide's
 * marks ([marks]), and every change to them. They are read when the server changes on a server that records
 * ([enabled]; the Recordings tab asks with [refresh] on any server), and again after every change made here. [apis]
 * null leaves the state [RecordingsState.Unconfigured]; [enabled] false leaves [marks] empty.
 */
class RecordingSchedule internal constructor(scope: CoroutineScope, apis: Flow<SofaKingApi?>, enabled: Flow<Boolean>) {
    private val _state = MutableStateFlow<RecordingsState>(RecordingsState.Loading)
    val state: StateFlow<RecordingsState> = _state.asStateFlow()

    private val _marks = MutableStateFlow(RecordingMarks())
    val marks: StateFlow<RecordingMarks> = _marks.asStateFlow()

    @Volatile private var api: SofaKingApi? = null

    @Volatile private var records = false
    private val mutex = Mutex()

    init {
        scope.launch {
            combine(apis, enabled, ::Pair).collectLatest { (found, on) ->
                mutex.withLock {
                    records = on
                    if (found !== api) {
                        api = found
                        _state.value = if (found == null) RecordingsState.Unconfigured else RecordingsState.Loading
                    }
                    publish()
                }
                if (on && _state.value !is RecordingsState.Ready) refresh()
            }
        }
    }

    /** Reads the recordings and rules again, keeping what is on screen until they are in; a failure keeps it too. */
    suspend fun refresh() = mutex.withLock {
        val api = api ?: return@withLock
        if (_state.value !is RecordingsState.Ready) _state.value = RecordingsState.Loading
        _state.value = try {
            coroutineScope {
                val list = async { api.recordings() }
                val rules = async { api.recordingRules().rules.map { it.toModel() } }
                val (recordings, storage) = list.await().let { it.recordings to it.storage }
                RecordingsState.Ready(
                    recordings.mapNotNull {
                        it.toModel()
                    },
                    RecordingSpace(storage.freeBytes, storage.totalBytes),
                    rules.await(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SofaKingException) {
            if (e.status == 404) RecordingsState.Unavailable else failed(e)
        } catch (e: Exception) {
            failed(e)
        }
        publish()
    }

    private fun failed(e: Exception): RecordingsState =
        failedLoad("recordings", e, _state.value as? RecordingsState.Ready, RecordingsState::Failed)

    /**
     * Records [programme], or cancels its recording when it has one. False when the server
     * refused or could not be reached.
     */
    suspend fun toggle(programme: Programme): Boolean = records && change("recording a program") {
        val id = _marks.value.recordings[recordingKey(programme.channelId, programme.start)]
        if (id != null) {
            deleteRecording(id)
        } else {
            scheduleRecording(ScheduleRecordingDto(programme.channelId, programme.start.toString()))
        }
    }

    /**
     * Records every airing of [programme]'s title on its channel, or deletes the series rule that takes it, with the
     * recordings that rule scheduled.
     */
    suspend fun toggleSeries(programme: Programme): Boolean = records && change("recording a series") {
        val id = _marks.value.ruleId(programme)
        if (id != null) {
            deleteRecordingRule(id)
        } else {
            createRecordingRule(CreateRecordingRuleDto(programme.title.trim(), programme.channelId))
        }
    }

    /** Cancels a scheduled recording, stops a running one, or deletes a finished one and its file. */
    internal suspend fun delete(recording: DvrRecording): Boolean = change("deleting a recording") {
        deleteRecording(recording.id)
        edit { it.copy(recordings = it.recordings.filterNot { r -> r.id == recording.id }) }
    }

    /** Changes [rule]: whether it takes new episodes only, and how many recordings it keeps (0 for all). */
    internal suspend fun updateRule(rule: SeriesRule, newOnly: Boolean, keep: Int): Boolean =
        change("changing a series rule") {
            val fresh = updateRecordingRule(rule.id, UpdateRecordingRuleDto(newOnly = newOnly, keep = keep)).toModel()
            edit { it.copy(rules = it.rules.map { r -> if (r.id == fresh.id) fresh else r }) }
        }

    /** Deletes [rule] and the recordings it scheduled; what it recorded stays. */
    internal suspend fun deleteRule(rule: SeriesRule): Boolean = change("deleting a series rule") {
        deleteRecordingRule(rule.id)
        edit { it.copy(rules = it.rules.filterNot { r -> r.id == rule.id }) }
    }

    /** Saves where the household is in [recordingId]; with [show] the list takes the server's answer. */
    internal suspend fun save(recordingId: String, update: UpdateRecordingDto, show: Boolean) {
        val api = api ?: return
        try {
            val fresh = api.updateRecording(recordingId, update).toModel()
            if (show && fresh != null) {
                edit {
                    it.copy(
                        recordings = it.recordings.map { r ->
                            if (r.id == fresh.id) {
                                fresh
                            } else {
                                r
                            }
                        },
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "saving the recording position failed: ${e.describe()}" }
        }
    }

    /** Runs [action] on the server, then reads the lists again whether it worked or not. False when it failed. */
    private suspend fun change(what: String, action: suspend SofaKingApi.() -> Unit): Boolean {
        val api = api ?: return false
        val done = try {
            api.action()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "$what failed: ${e.describe()}" }
            false
        }
        refresh()
        return done
    }

    /** Shows a change the server accepted at once, before the lists are read again. */
    private fun edit(change: (RecordingsState.Ready) -> RecordingsState.Ready) {
        _state.update { if (it is RecordingsState.Ready) change(it) else it }
        publish()
    }

    private fun publish() {
        val state = _state.value
        _marks.value = if (records && state is RecordingsState.Ready) state.marks() else RecordingMarks()
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
    }
}
