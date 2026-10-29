package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.OnLaterKind
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.ProgramFacts
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.RecordingWindow
import dev.jamesrich.sofaking.data.SeriesOptions
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.WatchlistMarks
import dev.jamesrich.sofaking.data.recordWindow
import dev.jamesrich.sofaking.data.toggleKeyword
import dev.jamesrich.sofaking.data.toggleTeam
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/**
 * What the guide, On Later and the live player do with a program: record it, its series or a team, put its title on
 * the household's watchlist, and look up what is coming up and what TMDB knows. Each change answers false when the
 * server refused it or could not be reached; the marks follow either way.
 */
interface ProgramActions {
    /** The server's scheduled and running recordings, as the guide marks them. */
    val recordingMarks: StateFlow<RecordingMarks>

    /** What the household's watchlist holds. */
    val watchlistMarks: StateFlow<WatchlistMarks>

    /** Records [program], or cancels its recording. */
    suspend fun toggleRecording(program: Program): Boolean

    /** Records every episode of [program]'s series with [options] for a new rule, or stops. */
    suspend fun toggleSeriesRecording(program: Program, options: SeriesOptions = SeriesOptions()): Boolean

    /** Records every game [team] plays, or stops. */
    suspend fun toggleTeamRecording(team: String): Boolean

    /** Records every program that names [query], or stops. */
    suspend fun toggleKeywordRecording(query: String): Boolean

    /** Records [channel] for [window] from now, with or without a guide. */
    suspend fun recordWindow(channel: Channel, window: RecordingWindow): Boolean

    /** Adds [program]'s title to the watchlist for every channel, or takes it off. */
    suspend fun toggleWatchlist(program: Program): Boolean

    /** What of [kind] is coming up on the lineup's channels, or null when the server cannot say. */
    suspend fun onLater(kind: OnLaterKind): List<Program>?

    /** What TMDB says about [program], through the server, for the break screen; null when nothing is known. */
    suspend fun programFacts(program: Program): ProgramFacts?
}

/** [ProgramActions] on the app's one [connection]. */
internal class ConnectionProgramActions(private val connection: ServerConnection) : ProgramActions {
    override val recordingMarks: StateFlow<RecordingMarks> get() = connection.recordings.marks
    override val watchlistMarks: StateFlow<WatchlistMarks> get() = connection.watchlist.marks

    override suspend fun toggleRecording(program: Program) = connection.recordings.toggle(program)

    override suspend fun toggleSeriesRecording(program: Program, options: SeriesOptions) =
        connection.recordings.toggleSeries(program, options)

    override suspend fun toggleTeamRecording(team: String) = connection.recordings.toggleTeam(team)

    override suspend fun toggleKeywordRecording(query: String) = connection.recordings.toggleKeyword(query)

    override suspend fun recordWindow(channel: Channel, window: RecordingWindow) =
        connection.recordings.recordWindow(channel.guideId, window, Clock.System.now())

    override suspend fun toggleWatchlist(program: Program) = connection.watchlist.toggleTitle(program)

    override suspend fun onLater(kind: OnLaterKind) = connection.onLater.programs(kind)

    override suspend fun programFacts(program: Program) = connection.programFacts.of(program)
}
