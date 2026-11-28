package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LiveHit
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.search
import dev.jamesrich.sofaking.ui.LocalClock
import dev.jamesrich.sofaking.ui.guide.AiringSheet
import dev.jamesrich.sofaking.ui.guide.GuideActions
import dev.jamesrich.sofaking.ui.guide.ProgramDetails
import dev.jamesrich.sofaking.ui.library.LibrarySearchActions
import dev.jamesrich.sofaking.ui.library.LibrarySearchScreen
import dev.jamesrich.sofaking.ui.library.LiveSearch
import dev.jamesrich.sofaking.ui.player.MusicPlayback
import dev.jamesrich.sofaking.ui.rememberMinuteNow

/**
 * Search across live TV and every library, from any tab; the query outlives a trip to an item's page or a channel
 * and back, in the entry's saved state. A channel or program opens its details, as On Later's do: watch the channel,
 * record the program or put it on the watchlist.
 */
@Composable
internal fun SearchDestination(
    route: Route.Search,
    arrival: Arrival,
    shell: AppShellState,
    vm: LineupViewModel,
    libraryVm: LibraryViewModel,
    music: MusicPlayback,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val recordingMarks by vm.recordingMarks.collectAsStateWithLifecycle()
    val watchlist by vm.watchlistMarks.collectAsStateWithLifecycle()
    val lineup = (state as? LineupState.Ready)?.visible
    val clock = LocalClock.current
    val sheet by rememberUpdatedState(
        LiveSheet(
            favorites = (settings ?: AppSettings()).favorites,
            recordingMarks = recordingMarks.takeIf { capabilities.recordings },
            actions = GuideActions(
                onPlay = { channel, _, _ -> shell.push(Route.Player(channel.guideId)) },
                onToggleFavorite = { vm.toggleFavorite(it.guideId) },
                onFilterChange = {},
                onLoadGuideWindow = {},
                onRecord = vm::toggleRecording,
                onRecordSeries = vm::toggleSeriesRecording,
                onRecordTeam = vm::toggleTeamRecording,
                onRecordWindow = vm::recordWindow,
                onWatchFromStart = { shell.push(Route.Recording(it, fromStart = true)) },
                onToggleWatchlist = vm::toggleWatchlist,
                watchlisted = { program -> watchlist.has(program).takeIf { watchlist.offered } },
            ),
        ),
    )
    val live = remember(lineup, clock) {
        lineup?.let { shown ->
            LiveSearch(
                find = { query -> shown.search(query, clock.now()) },
                details = { hit, onClose -> LiveHitDetails(hit, shown, sheet, onClose) },
            )
        }
    }
    LibrarySearchScreen(
        actions = LibrarySearchActions(
            search = libraryVm::search,
            onOpenItem = { shell.push(Route.LibraryItem(it.id)) },
            onPlayItem = { shell.playItem(music, it) },
            menu = cardMenu(shell, libraryVm),
            live = live,
        ),
        voice = arrival.search,
        returnedFrom = arrival.returnedFrom,
        initialQuery = route.query,
    )
}

/** What a live hit's details show and do: the favorite channels, what is recording, and the guide's actions. */
private class LiveSheet(val favorites: Set<String>, val recordingMarks: RecordingMarks?, val actions: GuideActions)

/** A live [hit]'s details over the search; watching its channel opens the player above the search. */
@Composable
private fun LiveHitDetails(hit: LiveHit, lineup: Lineup, sheet: LiveSheet, onClose: () -> Unit) {
    val now by rememberMinuteNow()
    var shown by remember(hit) { mutableStateOf(ProgramDetails(hit.channel, hit.program)) }
    AiringSheet(
        open = shown,
        now = now,
        lineup = lineup,
        favorites = sheet.favorites,
        recordingMarks = sheet.recordingMarks,
        actions = sheet.actions,
        onShow = { next -> if (next == null) onClose() else shown = next },
        onWatch = { channel ->
            onClose()
            sheet.actions.onPlay(channel, null, false)
        },
        onClose = onClose,
    )
}
