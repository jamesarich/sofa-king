package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.RuleMatch
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
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
     * Every recording but canceled ones, soonest scheduled first, then the rest newest first, and
     * the series rules, oldest first.
     */
    data class Ready(
        val recordings: List<DvrRecording>,
        val space: RecordingSpace,
        val rules: List<SeriesRule> = emptyList(),
    ) : RecordingsState
    data class Failed(val message: String) : RecordingsState
}

/**
 * Whether a program is set to record, on its own or through a series rule for its title, and [runningId], the
 * recording of it that is being written now, which plays from the program's start as it grows.
 */
data class RecordingMark(
    val recording: Boolean = false,
    val series: Boolean = false,
    val runningId: String? = null,
    /** The program's [teamSides] a team rule records, lowercase. */
    val teams: Set<String> = emptySet(),
    /** The recording is scheduled and loses its slot to others, so it will not happen as things stand. */
    val conflict: Boolean = false,
)

/** The server's scheduled recordings and series rules, as the guide marks programs with them. */
data class RecordingMarks(
    /** Scheduled and running recordings by [recordingKey], to their ids. */
    internal val recordings: Map<String, String> = emptyMap(),
    /** Series rules by [ruleKey], to their ids; a rule for any channel has no channel in its key. */
    internal val rules: Map<String, String> = emptyMap(),
    /** The recordings being written now, by [recordingKey], to their ids. */
    internal val running: Map<String, String> = emptyMap(),
    /** Team rules by each team they name, lowercase, to their ids. */
    internal val teams: Map<String, String> = emptyMap(),
    /** The scheduled recordings the server flags as in conflict, by [recordingKey]. */
    internal val conflicts: Set<String> = emptySet(),
    /** Keyword rules by each term they name, lowercase, to their ids. */
    internal val keywords: Map<String, String> = emptyMap(),
) {
    /** Whether a keyword rule records [query], by the term [keywordTerm] makes of it. */
    fun recordsKeyword(query: String): Boolean = keywordTerm(query)?.lowercase() in keywords

    fun of(program: Program): RecordingMark {
        val key = recordingKey(program.channelId, program.start)
        return RecordingMark(
            recording = key in recordings,
            series = ruleId(program) != null,
            runningId = running[key],
            conflict = key in conflicts,
            teams = teamSides(program).map { it.lowercase() }.filterTo(HashSet()) { it in teams },
        )
    }

    internal fun ruleId(program: Program): String? =
        rules[ruleKey(program.title, program.channelId)] ?: rules[ruleKey(program.title, null)]
}

private fun recordingKey(channelId: String, start: Instant) = "$channelId@${start.toEpochMilliseconds()}"

// The server matches a rule's title ignoring case and outer spaces.
private fun ruleKey(title: String, channelId: String?) = title.trim().lowercase() + "@" + channelId.orEmpty()

private fun RecordingsState.Ready.marks() = RecordingMarks(
    recordings = recordings.filter { it.state == RecordingState.SCHEDULED || it.state == RecordingState.RECORDING }
        .associate { recordingKey(it.channelId, it.startsAt) to it.id },
    rules = rules.filter { it.kind == Wire.RuleMatchKind.TITLE }.associate { ruleKey(it.title, it.channelId) to it.id },
    teams = rules.filter { it.kind == Wire.RuleMatchKind.TEAM }
        .flatMap { rule -> rule.terms.map { it.lowercase() to rule.id } }.toMap(),
    running = recordings.filter { it.state == RecordingState.RECORDING }
        .associate { recordingKey(it.channelId, it.startsAt) to it.id },
    conflicts = recordings.filter { it.state == RecordingState.SCHEDULED && it.conflict }
        .mapTo(HashSet()) { recordingKey(it.channelId, it.startsAt) },
    keywords = rules.filter { it.kind == Wire.RuleMatchKind.KEYWORD }
        .flatMap { rule -> rule.terms.map { it.lowercase() to rule.id } }.toMap(),
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

    @Volatile internal var records = false
        private set
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
        _state.value = runCatchingCancellable {
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
        }.getOrElse { e ->
            if (e is SofaKingException && e.statusCode == HttpStatusCode.NotFound) {
                RecordingsState.Unavailable
            } else {
                failed(e)
            }
        }
        publish()
    }

    private fun failed(e: Throwable): RecordingsState =
        failedLoad("recordings", e, _state.value as? RecordingsState.Ready, RecordingsState::Failed)

    /**
     * Records [program], or cancels its recording when it has one. False when the server
     * refused or could not be reached.
     */
    suspend fun toggle(program: Program): Boolean = records && change("recording a program") {
        val id = _marks.value.recordings[recordingKey(program.channelId, program.start)]
        if (id != null) {
            deleteRecording(id)
        } else {
            scheduleRecording(ScheduleRecordingDto(program.channelId, program.start.toString()))
        }
    }

    /**
     * Records every airing of [program]'s title on its channel, or deletes the series rule that takes it, with the
     * recordings that rule scheduled. A new rule takes [options]: its padding, and any channel instead of this one.
     */
    suspend fun toggleSeries(program: Program, options: SeriesOptions = SeriesOptions()): Boolean =
        records && change("recording a series") {
            val id = _marks.value.ruleId(program)
            if (id != null) {
                deleteRecordingRule(id)
            } else {
                createRecordingRule(seriesRule(program, options))
            }
        }

    /** Cancels a scheduled recording, stops a running one, or deletes a finished one and its file. */
    internal suspend fun delete(recording: DvrRecording): Boolean = change("deleting a recording") {
        deleteRecording(recording.id)
        edit { it.copy(recordings = it.recordings.filterNot { r -> r.id == recording.id }) }
    }

    /**
     * Changes [rule]: whether it takes new episodes only, how many recordings it keeps (0 for all), its [padding]
     * when set, and with [anyChannel] the channel it is limited to.
     */
    internal suspend fun updateRule(
        rule: SeriesRule,
        newOnly: Boolean,
        keep: Int,
        padding: RecordingPadding? = null,
        anyChannel: Boolean = false,
    ): Boolean = change("changing a series rule") {
        val update = UpdateRecordingRuleDto(
            newOnly = newOnly,
            keep = keep,
            startPaddingSeconds = padding?.startSeconds,
            endPaddingSeconds = padding?.endSeconds,
            anyChannel = anyChannel.takeIf { it },
        )
        val fresh = updateRecordingRule(rule.id, update).toModel()
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
        runCatchingCancellable {
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
        }.onFailure { e ->
            log.w { "saving the recording position failed: ${e.describe()}" }
        }
    }

    /** Runs [action] on the server, then reads the lists again whether it worked or not. False when it failed. */
    internal suspend fun change(what: String, action: suspend SofaKingApi.() -> Unit): Boolean {
        val api = api ?: return false
        val done = runCatchingCancellable {
            api.action()
            true
        }.getOrElse { e ->
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

/** Has cleanup keep [recording] or not; the list then reads the server's state, so a refusal shows as it was. */
internal suspend fun RecordingSchedule.setProtected(recording: DvrRecording, protect: Boolean): Boolean =
    change("protecting a recording") {
        updateRecording(recording.id, UpdateRecordingDto(protected = protect))
    }

/**
 * Records every sports program that names [team] in its title or episode title, on any channel, or deletes the team's
 * rule with the recordings it scheduled.
 */
suspend fun RecordingSchedule.toggleTeam(team: String): Boolean = records && change("recording a team") {
    val id = marks.value.teams[team.trim().lowercase()]
    if (id != null) {
        deleteRecordingRule(id)
    } else {
        val match = RuleMatch(Wire.RuleMatchKind.TEAM, listOf(team.trim()))
        createRecordingRule(CreateRecordingRuleDto(team.trim(), match = match))
    }
}

/**
 * Records [channelId] for [window] from [now], whatever the guide lists; the only way to record a channel with no
 * guide. False when the server refused or could not be reached.
 */
suspend fun RecordingSchedule.recordWindow(channelId: String, window: RecordingWindow, now: Instant): Boolean =
    records && change("recording a window") {
        scheduleRecording(windowRecording(channelId, window, now))
    }

/**
 * Records every program that names [query] as a word or phrase in its title, episode title or description, on any
 * channel, or deletes the keyword rule that does with the recordings it scheduled.
 */
suspend fun RecordingSchedule.toggleKeyword(query: String): Boolean {
    val term = keywordTerm(query) ?: return false
    return records && change("recording a keyword") {
        val id = marks.value.keywords[term.lowercase()]
        if (id != null) deleteRecordingRule(id) else createRecordingRule(keywordRule(term))
    }
}
