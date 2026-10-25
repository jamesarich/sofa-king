package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.IntroSpan
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.KidsMode
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.ScrubTiles
import dev.jamesrich.sofaking.data.SeekSteps
import dev.jamesrich.sofaking.data.Skips
import dev.jamesrich.sofaking.data.SubtitleLook
import dev.jamesrich.sofaking.data.defaultAudio
import dev.jamesrich.sofaking.data.initialAudio
import dev.jamesrich.sofaking.data.initialSeasonId
import dev.jamesrich.sofaking.data.initialSubtitle
import dev.jamesrich.sofaking.data.isPictureSubtitle
import dev.jamesrich.sofaking.data.trackChoice
import dev.jamesrich.sofaking.data.trackLabel
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_load_failed
import dev.jamesrich.sofaking.resources.library_loading
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.retry
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.LocalKidsMode
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.library.CardMenu
import dev.jamesrich.sofaking.ui.library.ItemDetailsActions
import dev.jamesrich.sofaking.ui.library.ItemDetailsScreen
import dev.jamesrich.sofaking.ui.library.LibraryGridActions
import dev.jamesrich.sofaking.ui.library.LibraryGridScreen
import dev.jamesrich.sofaking.ui.library.LibraryHomeActions
import dev.jamesrich.sofaking.ui.library.LibraryHomeScreen
import dev.jamesrich.sofaking.ui.library.LibrarySearchActions
import dev.jamesrich.sofaking.ui.library.LibrarySearchScreen
import dev.jamesrich.sofaking.ui.library.stillOrBackdrop
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.VodMarker
import dev.jamesrich.sofaking.ui.player.VodMarkerKind
import dev.jamesrich.sofaking.ui.player.VodPlayerScreen
import dev.jamesrich.sofaking.ui.player.VodTitle
import dev.jamesrich.sofaking.ui.player.VodTrack
import dev.jamesrich.sofaking.ui.player.VodTracks
import dev.jamesrich.sofaking.ui.player.VodUpNext
import org.jetbrains.compose.resources.stringResource

/** What a library card's menu does on every library page: play from the start, or mark watched. */
private fun cardMenu(shell: AppShellState, libraryVm: LibraryViewModel) = CardMenu(
    onPlayFromStart = { shell.push(Route.Vod(it.id, fromStart = true)) },
    onSetWatched = libraryVm::setWatched,
)

/** The Library tab's first page: the shelves. [tabBack] climbs to the header, then opens Live. */
@Composable
internal fun LibraryHomeDestination(
    arrival: Arrival,
    shell: AppShellState,
    libraryVm: LibraryViewModel,
    tabBack: () -> Unit,
    headerFocused: Boolean,
) {
    val home by libraryVm.home.collectAsStateWithLifecycle()
    val kids = LocalKidsMode.current
    BackHandler(onBack = tabBack)
    LibraryHomeScreen(
        state = home.let { shown ->
            if (kids && shown is LibraryHomeState.Ready) LibraryHomeState.Ready(KidsMode.home(shown.home)) else shown
        },
        actions = LibraryHomeActions(
            onOpenItem = { shell.push(Route.LibraryItem(it.id)) },
            onPlayItem = { shell.push(Route.Vod(it.id)) },
            onOpenLibrary = { shell.push(Route.LibraryGrid(it.id)) },
            onRetry = libraryVm::refreshHome,
            onSearch = { voice -> shell.push(Route.LibrarySearch(), Arrival(search = voice)) },
            menu = cardMenu(shell, libraryVm),
            onRemoveFromContinue = libraryVm::removeFromContinue,
        ),
        returnedFrom = arrival.returnedFrom,
        headerFocused = headerFocused,
    )
}

/** Search across every library; the query outlives a trip to an item's page and back, in the entry's saved state. */
@Composable
internal fun LibrarySearchDestination(
    route: Route.LibrarySearch,
    arrival: Arrival,
    shell: AppShellState,
    libraryVm: LibraryViewModel,
) {
    val kids = LocalKidsMode.current
    LibrarySearchScreen(
        actions = LibrarySearchActions(
            search = { query ->
                libraryVm.search(query).let { found -> if (kids) found.filter(KidsMode::allows) else found }
            },
            onOpenItem = { shell.push(Route.LibraryItem(it.id)) },
            onPlayItem = { shell.push(Route.Vod(it.id)) },
            menu = cardMenu(shell, libraryVm),
        ),
        voice = arrival.search,
        returnedFrom = arrival.returnedFrom,
        initialQuery = route.query,
    )
}

/** One library's grid, kept in the order, filter and genre it was last shown in. */
@Composable
internal fun LibraryGridDestination(
    route: Route.LibraryGrid,
    arrival: Arrival,
    shell: AppShellState,
    libraryVm: LibraryViewModel,
) {
    val home by libraryVm.home.collectAsStateWithLifecycle()
    val grid by libraryVm.grid.collectAsStateWithLifecycle()
    LaunchedEffect(route.libraryId) {
        val open = grid
        libraryVm.openGrid(
            route.libraryId,
            open?.sort ?: LibrarySort.TITLE,
            open?.unwatched ?: false,
            open?.takeIf { it.libraryId == route.libraryId }?.genre,
        )
    }
    val kids = LocalKidsMode.current
    val shown = grid?.takeIf { it.libraryId == route.libraryId }?.let { if (kids) kidsGrid(it) else it }
    // Kids mode leaves some pages thin, so the next loads until the grid fills a screen or the library ends.
    LaunchedEffect(kids, shown?.items?.size, shown?.next, shown?.loading) {
        if (kids && shown != null && kidsGridThin(shown)) libraryVm.loadMore()
    }
    LibraryGridScreen(
        library = (home as? LibraryHomeState.Ready)?.home?.libraries?.firstOrNull { it.id == route.libraryId },
        grid = shown,
        actions = LibraryGridActions(
            onSort = { libraryVm.openGrid(route.libraryId, it, shown?.unwatched ?: false, shown?.genre) },
            onUnwatched = { libraryVm.openGrid(route.libraryId, shown?.sort ?: LibrarySort.TITLE, it, shown?.genre) },
            onGenre = {
                libraryVm.openGrid(route.libraryId, shown?.sort ?: LibrarySort.TITLE, shown?.unwatched ?: false, it)
            },
            onOpenItem = { shell.push(Route.LibraryItem(it.id)) },
            onPlayItem = { shell.push(Route.Vod(it.id)) },
            onLoadMore = libraryVm::loadMore,
            onLoadThrough = libraryVm::loadThrough,
            menu = cardMenu(shell, libraryVm),
        ),
        returnedFrom = arrival.returnedFrom,
    )
}

/**
 * [grid] down to the titles kids mode shows. Its letters go, since they jump to places in the whole library, and
 * its total counts what is shown so far.
 */
private fun kidsGrid(grid: LibraryGrid): LibraryGrid {
    val items = grid.items.filter(KidsMode::allows)
    return grid.copy(items = items, letters = emptyList(), total = items.size)
}

/** Whether a kids mode [grid] has fewer titles than fill a screen and more pages to load. */
private fun kidsGridThin(grid: LibraryGrid): Boolean =
    !grid.loading && grid.next != null && grid.items.size < KIDS_GRID_FILL

/** How many titles kids mode loads into a grid before it waits for the viewer to scroll. */
private const val KIDS_GRID_FILL = 24

/** A movie, show, season or episode's page; a show lists the season picked, else the one with the next episode. */
@Composable
internal fun LibraryItemDestination(
    route: Route.LibraryItem,
    arrival: Arrival,
    shell: AppShellState,
    libraryVm: LibraryViewModel,
    hideSpoilers: Boolean,
) {
    val detail by remember(route.itemId) { libraryVm.detail(route.itemId) }.collectAsStateWithLifecycle()
    val ready = (detail as? ItemDetailState.Ready)?.detail
    var pickedSeason by rememberSaveable(route.itemId) { mutableStateOf<String?>(null) }
    val seasonId = pickedSeason ?: ready?.let(::initialSeasonId)
    val season = seasonId?.let { id -> remember(id) { libraryVm.detail(id) }.collectAsStateWithLifecycle().value }
    val watchlist by libraryVm.watchlistMarks.collectAsStateWithLifecycle()
    ItemDetailsScreen(
        state = detail,
        season = season,
        selectedSeasonId = seasonId,
        actions = ItemDetailsActions(
            onPlay = { item, fromStart -> shell.push(Route.Vod(item.id, fromStart)) },
            onSetWatched = libraryVm::setWatched,
            onSelectSeason = { pickedSeason = it },
            onRetry = { libraryVm.detail(route.itemId, refresh = true) },
            onPerson = { shell.push(Route.LibrarySearch(query = it)) },
            onOpenShow = { shell.push(Route.LibraryItem(it)) },
            onOpenItem = { shell.push(Route.LibraryItem(it.id)) },
            onToggleWatchlist = libraryVm::toggleWatchlist,
            watchlisted = { item -> watchlist.has(item).takeIf { watchlist.offered } },
        ),
        returnedFrom = arrival.returnedFrom,
        hideSpoilers = hideSpoilers,
    )
}

/**
 * A library item full screen, with its audio and subtitle tracks and Up next. The tracks picked in a show start its
 * next episodes.
 */
@Composable
internal fun VodDestination(
    route: Route.Vod,
    shell: AppShellState,
    vm: LineupViewModel,
    libraryVm: LibraryViewModel,
    player: LivePlayer,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val detail by remember(route.itemId) { libraryVm.detail(route.itemId) }.collectAsStateWithLifecycle()
    val ready = (detail as? ItemDetailState.Ready)?.detail
    when {
        // Keyed by the item, so the next episode starts a fresh screen rather than inheriting this one's end.
        ready != null -> key(ready.item.id) {
            val item = ready.item
            var next by remember { mutableStateOf<MediaItem?>(null) }
            // The tracks the viewer picked in this show start its episodes, read once as the item opens.
            val choice = remember { item.showId?.let { settings?.trackChoices?.get(it) } }
            val startAudio = remember { initialAudio(ready, choice) }
            val startSubtitle = remember { initialSubtitle(ready, choice) }
            // The tracks the stream carries, so a retry opens the item with them again: an audio track other
            // than the default, and a picture subtitle drawn in from the first stream.
            var audio by remember { mutableStateOf(startAudio?.index?.takeIf { it != defaultAudio(ready)?.index }) }
            var pictureSubtitle by remember { mutableStateOf(startSubtitle?.takeIf(::isPictureSubtitle)?.index) }
            val tracks = remember(ready) {
                val initial = startAudio?.index ?: defaultAudio(ready)?.index
                vodTracks(ready, initial, startSubtitle?.index, vm, libraryVm, player) { audioIndex, subtitleIndex ->
                    audio = audioIndex
                    pictureSubtitle = subtitleIndex
                }
            }
            VodPlayerScreen(
                title = item.vodTitle(ready.intro, ready.credits, ready.scrub),
                player = player,
                sessions = vm.sessions,
                itemId = item.id,
                open = {
                    libraryVm.play(item, player.playbackProfile, route.fromStart, audio, pictureSubtitle, route.startMs)
                },
                onPosition = { position, duration -> libraryVm.progress(item.id, position, duration) },
                onStop = { position, duration -> libraryVm.stopped(item.id, position, duration) },
                onExit = shell::back,
                upNext = { libraryVm.upNext(item)?.also { next = it }?.upNext() },
                onPlayNext = { next?.let { shell.replaceTop(Route.Vod(it.id)) } },
                skips = settings?.skips ?: Skips(),
                seekSteps = settings?.seekSteps ?: SeekSteps(),
                subtitleLook = settings?.subtitleLook ?: SubtitleLook(),
                tracks = tracks,
                zoom = shell.videoZoom,
                onZoom = { shell.videoZoom = it },
                sleep = shell.sleep,
                autoplays = shell.autoplays,
            )
        }

        detail is ItemDetailState.Failed -> MessageScreen(
            message = stringResource(Res.string.library_load_failed),
            detail = (detail as ItemDetailState.Failed).message,
            onRetry = { libraryVm.detail(route.itemId, refresh = true) },
        )

        else -> MessageScreen(stringResource(Res.string.library_loading), loading = true)
    }
}

/**
 * [d]'s audio and subtitle tracks for the player. A pick is kept for the show, and a switch reopens the stream with
 * the new tracks, telling [onSelect] which ones it carries.
 */
private fun vodTracks(
    d: MediaItemDetail,
    initialAudio: Int?,
    initialSubtitle: Int?,
    vm: LineupViewModel,
    libraryVm: LibraryViewModel,
    player: LivePlayer,
    onSelect: (audio: Int?, subtitle: Int?) -> Unit,
): VodTracks {
    val item = d.item
    return VodTracks(
        audio = d.audio.mapIndexed { i, t -> VodTrack(t.index, trackLabel(t, i + 1)) },
        subtitles = d.subtitles.mapIndexed { i, t ->
            VodTrack(t.index, trackLabel(t, i + 1), isPictureSubtitle(t))
        },
        initialAudio = initialAudio,
        initialSubtitle = initialSubtitle,
        onPicked = { audioIndex, subtitleIndex ->
            item.showId?.let { vm.setTrackChoice(it, trackChoice(d, audioIndex, subtitleIndex)) }
        },
        select = { audioIndex, subtitleIndex, position ->
            onSelect(audioIndex, subtitleIndex)
            libraryVm.switchTracks(item, player.playbackProfile, position, audioIndex, subtitleIndex)
        },
        loadSubtitles = { index -> libraryVm.subtitles(item.id, index) },
    )
}

/**
 * What the player shows for a library item: an episode under its show and number, a movie with its year,
 * its [intro] and [credits], and its [scrub] thumbnails.
 */
private fun MediaItem.vodTitle(intro: IntroSpan?, credits: IntroSpan?, scrub: ScrubTiles?): VodTitle {
    val subtitle = when (kind) {
        ItemKind.EPISODE -> listOfNotNull(showTitle, episodeLabel).joinToString(" · ").ifEmpty { null }
        else -> year?.toString()
    }
    val markers = listOfNotNull(
        intro?.let { VodMarker(it.startMs, it.endMs, VodMarkerKind.INTRO) },
        credits?.let { VodMarker(it.startMs, it.endMs, VodMarkerKind.CREDITS) },
    )
    return VodTitle(title, subtitle, artworkUrl = backdrop ?: poster, markers = markers, scrub = scrub)
}

/** The Up next card for an episode: its number and title under the show, with its still. */
private fun MediaItem.upNext(): VodUpNext = VodUpNext(
    title = title,
    subtitle = listOfNotNull(showTitle, episodeLabel).joinToString(" · ").ifEmpty { null },
    artworkUrl = stillOrBackdrop(),
)
