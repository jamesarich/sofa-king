package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

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

    internal fun ruleId(programme: Programme): String? = rules[ruleKey(programme.title, programme.channelId)] ?: rules[ruleKey(programme.title, null)]
}

private fun recordingKey(channelId: String, start: Instant) = "$channelId@${start.toEpochMilliseconds()}"

// The server matches a rule's title ignoring case and outer spaces.
private fun ruleKey(title: String, channelId: String?) = title.trim().lowercase() + "@" + channelId.orEmpty()

/**
 * Records programmes from the guide on a server that records ([apis] null or [enabled] false leaves [marks] empty).
 * The marks are read again whenever the server changes and after every change made here.
 */
class RecordingSchedule internal constructor(scope: CoroutineScope, apis: Flow<SofaKingApi?>, enabled: Flow<Boolean>) {
    private val _marks = MutableStateFlow(RecordingMarks())
    val marks: StateFlow<RecordingMarks> = _marks.asStateFlow()

    private var api: SofaKingApi? = null
    private val mutex = Mutex()

    init {
        scope.launch {
            combine(apis, enabled) { api, on -> api.takeIf { on } }.collectLatest { found ->
                mutex.withLock { api = found }
                refresh()
            }
        }
    }

    /** Reads the recordings and rules again; a failure keeps the marks the guide already shows. */
    suspend fun refresh() = mutex.withLock {
        val api = api ?: run { _marks.value = RecordingMarks(); return@withLock }
        try {
            val upcoming = api.recordings().recordings.filter { it.state == Wire.RecordingState.SCHEDULED || it.state == Wire.RecordingState.RECORDING }
            val rules = api.recordingRules().rules
            _marks.value = RecordingMarks(
                recordings = upcoming.mapNotNull { r -> parseInstant(r.startsAt)?.let { recordingKey(r.channelId, it) to r.id } }.toMap(),
                rules = rules.associate { ruleKey(it.title, it.channelId) to it.id },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "recordings not read: ${e.message}" }
        }
    }

    /** Records [programme], or cancels its recording when it has one. False when the server refused or could not be reached. */
    suspend fun toggle(programme: Programme): Boolean = change {
        val id = _marks.value.recordings[recordingKey(programme.channelId, programme.start)]
        if (id != null) deleteRecording(id) else scheduleRecording(ScheduleRecordingDto(programme.channelId, programme.start.toString()))
    }

    /**
     * Records every airing of [programme]'s title on its channel, or deletes the series rule that takes it, with the
     * recordings that rule scheduled.
     */
    suspend fun toggleSeries(programme: Programme): Boolean = change {
        val id = _marks.value.ruleId(programme)
        if (id != null) deleteRecordingRule(id) else createRecordingRule(CreateRecordingRuleDto(programme.title.trim(), programme.channelId))
    }

    private suspend fun change(action: suspend SofaKingApi.() -> Unit): Boolean {
        val api = mutex.withLock { api } ?: return false
        val done = try {
            api.action()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "recording change refused: ${(e as? SofaKingException)?.code ?: e.message}" }
            false
        }
        refresh()
        return done
    }

    private fun parseInstant(text: String): Instant? = try { Instant.parse(text) } catch (_: IllegalArgumentException) { null }

    private companion object {
        val log = Logger.withTag("SofaKing")
    }
}
