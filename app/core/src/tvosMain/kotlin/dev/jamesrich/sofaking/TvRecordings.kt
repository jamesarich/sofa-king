package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.data.SeriesRule
import dev.jamesrich.sofaking.data.recordedSoFar
import dev.jamesrich.sofaking.data.resumeFraction
import dev.jamesrich.sofaking.data.teamSides
import dev.jamesrich.sofaking.data.toggleTeam
import kotlin.time.Clock
import kotlin.time.Instant

/** The server's recordings and series rules, as the Apple TV app's Recordings tab shows them. */
interface TvRecordings {
    fun watchRecordings(onChange: (RecordingsState) -> Unit): Watch

    fun refreshRecordings()

    /** Stops, cancels or deletes [recording]; false when the server refused or could not be reached. */
    @Throws(Exception::class)
    suspend fun deleteRecording(recording: DvrRecording): Boolean

    /** Opens [recording] on the session, from the start with [fromStart], else where the household stopped in it. */
    @Throws(Exception::class)
    suspend fun playRecording(recording: DvrRecording, fromStart: Boolean): PlaybackStream

    /** How far into [recording] the household got, 0 to 1; 0 when it has not started it or finished it. */
    fun recordingResume(recording: DvrRecording): Float

    /** How much of a running [recording] is on disk at [nowMs], milliseconds since the epoch, 0 to 1; else 0. */
    fun recordingRecorded(recording: DvrRecording, nowMs: Long): Float

    /** The player is at [positionMs] in [recordingId]. */
    fun recordingProgress(recordingId: String, positionMs: Long)

    /** Playback of [recordingId] stopped at [positionMs]; saved now, watched near the end. */
    fun recordingStopped(recordingId: String, positionMs: Long, durationMs: Long)

    /** Sets whether [rule] takes new episodes only and how many recordings it keeps (0 for all). */
    @Throws(Exception::class)
    suspend fun updateRule(rule: SeriesRule, newOnly: Boolean, keep: Int): Boolean

    /** Stops [rule]: it and the recordings it scheduled go, what it recorded stays. */
    @Throws(Exception::class)
    suspend fun deleteRule(rule: SeriesRule): Boolean
}

/** The guide's record marks, as the Apple TV app's guide and program sheet show and change them. */
interface TvRecordingMarks {
    /** Which guide programs are set to record, on their own or by a series rule. */
    fun watchRecordingMarks(onChange: (RecordingMarks) -> Unit): Watch

    /** Whether [program] records, by [marks]. */
    fun recordingMark(marks: RecordingMarks, program: Program): RecordingMark

    /** True until [program] ends: only then can it still be recorded. */
    fun recordable(program: Program): Boolean

    /** The recording of [program] being written now, by [marks], which plays from the program's start; else null. */
    fun runningRecording(marks: RecordingMarks, program: Program): DvrRecording?

    /** Records [program], or cancels its recording; false when the server refused or could not be reached. */
    @Throws(Exception::class)
    suspend fun toggleRecording(program: Program): Boolean

    /** Records every airing of [program]'s title on its channel, or stops the series rule that takes it. */
    @Throws(Exception::class)
    suspend fun toggleSeries(program: Program): Boolean

    /** The two teams a sports program names, in its own spelling; empty for any other program. */
    fun teams(program: Program): List<String>

    /** Whether a team rule records [team], by [mark]. */
    fun recordsTeam(mark: RecordingMark, team: String): Boolean

    /** Records every game [team] plays, on any channel, or stops; false when the server refused. */
    @Throws(Exception::class)
    suspend fun toggleTeam(team: String): Boolean
}

internal class TvRecordingCalls(private val parts: TvParts) : TvRecordings {
    private val recordings = parts.recordings

    override fun watchRecordings(onChange: (RecordingsState) -> Unit): Watch = parts.watch(recordings.state, onChange)

    override fun refreshRecordings() = recordings.refresh()

    @Throws(Exception::class)
    override suspend fun deleteRecording(recording: DvrRecording): Boolean = recordings.delete(recording)

    @Throws(Exception::class)
    override suspend fun playRecording(recording: DvrRecording, fromStart: Boolean): PlaybackStream =
        parts.opening { recordings.play(recording, appleTvProfile, fromStart) }

    override fun recordingResume(recording: DvrRecording): Float = recording.resumeFraction ?: 0f

    override fun recordingRecorded(recording: DvrRecording, nowMs: Long): Float =
        recording.recordedSoFar(Instant.fromEpochMilliseconds(nowMs)) ?: 0f

    override fun recordingProgress(recordingId: String, positionMs: Long) = recordings.progress(recordingId, positionMs)

    override fun recordingStopped(recordingId: String, positionMs: Long, durationMs: Long) =
        recordings.stopped(recordingId, positionMs, durationMs.takeIf { it > 0 })

    @Throws(Exception::class)
    override suspend fun updateRule(rule: SeriesRule, newOnly: Boolean, keep: Int): Boolean =
        recordings.updateRule(rule, newOnly, keep)

    @Throws(Exception::class)
    override suspend fun deleteRule(rule: SeriesRule): Boolean = recordings.deleteRule(rule)
}

internal class TvRecordingMarkCalls(private val parts: TvParts) : TvRecordingMarks {
    private val marks = parts.connection.recordings

    override fun watchRecordingMarks(onChange: (RecordingMarks) -> Unit): Watch = parts.watch(marks.marks, onChange)

    override fun recordingMark(marks: RecordingMarks, program: Program): RecordingMark = marks.of(program)

    override fun recordable(program: Program): Boolean = program.stop > Clock.System.now()

    override fun runningRecording(marks: RecordingMarks, program: Program): DvrRecording? =
        marks.of(program).runningId?.let(parts.recordings::recording)

    @Throws(Exception::class)
    override suspend fun toggleRecording(program: Program): Boolean = marks.toggle(program)

    @Throws(Exception::class)
    override suspend fun toggleSeries(program: Program): Boolean = marks.toggleSeries(program)

    override fun teams(program: Program): List<String> = teamSides(program)

    override fun recordsTeam(mark: RecordingMark, team: String): Boolean = team.lowercase() in mark.teams

    @Throws(Exception::class)
    override suspend fun toggleTeam(team: String): Boolean = marks.toggleTeam(team)
}
