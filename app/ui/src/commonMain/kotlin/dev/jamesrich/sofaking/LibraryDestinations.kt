package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.IntroSpan
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.MusicView
import dev.jamesrich.sofaking.data.ScrubTiles
import dev.jamesrich.sofaking.data.VodMarker
import dev.jamesrich.sofaking.data.VodMarkerKind
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
import dev.jamesrich.sofaking.ui.player.MusicPlayback
import dev.jamesrich.sofaking.ui.player.VodPlayerScreen
import dev.jamesrich.sofaking.ui.player.VodTitle
import dev.jamesrich.sofaking.ui.player.VodTrack
import dev.jamesrich.sofaking.ui.player.VodTracks
import dev.jamesrich.sofaking.ui.player.VodUpNext
import kotlinx.coroutines.launch
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
    music: MusicPlayback,
    tabBack: () -> Unit,
    headerFocused: Boolean,
) {
    val home by libraryVm.home.collectAsStateWithLifecycle()
    BackHandler(onBack = tabBack)
    LibraryHomeScreen(
        state = home,
        actions = LibraryHomeActions(
            onOpenItem = { shell.push(Route.LibraryItem(it.id)) },
            onPlayItem = { shell.playItem(music, it) },
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
    music: MusicPlayback,
) {
    LibrarySearchScreen(
        actions = LibrarySearchActions(
            search = libraryVm::search,
            onOpenItem = { shell.push(Route.LibraryItem(it.id)) },
            onPlayItem = { shell.playItem(music, it) },
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
    music: MusicPlayback,
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
            open?.takeIf { it.libraryId == route.libraryId }?.view ?: MusicView.ARTISTS,
        )
    }
    val shown = grid?.takeIf { it.libraryId == route.libraryId }
    val view = shown?.view ?: MusicView.ARTISTS
    LibraryGridScreen(
        library = (home as? LibraryHomeState.Ready)?.home?.libraries?.firstOrNull { it.id == route.libraryId },
        grid = shown,
        actions = LibraryGridActions(
            onSort = { libraryVm.openGrid(route.libraryId, it, shown?.unwatched ?: false, shown?.genre, view) },
            onUnwatched = {
                libraryVm.openGrid(route.libraryId, shown?.sort ?: LibrarySort.TITLE, it, shown?.genre, view)
            },
            onGenre = {
                libraryVm.openGrid(
                    route.libraryId,
                    shown?.sort ?: LibrarySort.TITLE,
                    shown?.unwatched ?: false,
                    it,
                    view,
                )
            },
            // Another view keeps its own genres and may not offer the sort, so it opens by title.
            onView = { libraryVm.openGrid(route.libraryId, LibrarySort.TITLE, false, null, it) },
            onOpenItem = { shell.nav = shell.nav.pushOver(route, Route.LibraryItem(it.id)) },
            onPlayItem = { shell.playItem(music, it) },
            onLoadMore = libraryVm::loadMore,
            onLoadThrough = libraryVm::loadThrough,
            menu = cardMenu(shell, libraryVm),
        ),
        returnedFrom = arrival.returnedFrom,
    )
}

/** A movie, show, season or episode's page; a show lists the season picked, else the one with the next episode. */
@Composable
internal fun LibraryItemDestination(
    route: Route.LibraryItem,
    arrival: Arrival,
    shell: AppShellState,
    libraryVm: LibraryViewModel,
    music: MusicPlayback,
    hideSpoilers: Boolean,
    page: ItemPageViewModel = viewModel { ItemPageViewModel(libraryVm::openItem, route.itemId) },
) {
    val detail by page.detail.collectAsStateWithLifecycle()
    val ready = (detail as? ItemDetailState.Ready)?.detail
    var pickedSeason by rememberSaveable(route.itemId) { mutableStateOf<String?>(null) }
    val seasonId = pickedSeason ?: ready?.let(::initialSeasonId)
    val season = seasonId?.let { id -> remember(id) { page.season(id) }.collectAsStateWithLifecycle().value }
    val watchlist by libraryVm.watchlistMarks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ItemDetailsScreen(
        state = detail,
        season = season,
        selectedSeasonId = seasonId,
        actions = ItemDetailsActions(
            onPlay = { item, fromStart ->
                // A song picked on its album's page plays on through the album, and one of an artist's Popular
                // songs on through those.
                val album = ready?.takeIf { it.item.kind == ItemKind.ALBUM }?.children
                    ?: ready?.popular?.takeIf { songs -> songs.any { it.id == item.id } } ?: listOf(item)
                shell.playItem(music, item, fromStart, album)
            },
            onSetWatched = libraryVm::setWatched,
            onSelectSeason = { pickedSeason = it },
            onRetry = page::retry,
            onRetrySeason = page::retrySeason,
            onPerson = { shell.push(Route.LibrarySearch(query = it)) },
            onOpenShow = { shell.push(Route.LibraryItem(it)) },
            onOpenItem = { shell.push(Route.LibraryItem(it.id)) },
            onToggleWatchlist = libraryVm::toggleWatchlist,
            watchlisted = { item -> watchlist.has(item).takeIf { watchlist.offered } },
            onShuffle = { shell.shuffle(music, ready?.children.orEmpty()) },
            onRadio = { ready?.let { scope.launch { shell.playRadio(music, libraryVm.radio(it.item)) } } },
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
    page: ItemPageViewModel = viewModel { ItemPageViewModel(libraryVm::openItem, route.itemId) },
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val prefs = settings ?: AppSettings()
    val detail by page.detail.collectAsStateWithLifecycle()
    val ready = (detail as? ItemDetailState.Ready)?.detail
    when {
        // Keyed by the item, so the next episode starts a fresh screen rather than inheriting this one's end.
        ready != null -> key(ready.item.id) {
            val item = ready.item
            var next by remember { mutableStateOf<MediaItem?>(null) }
            // The tracks the viewer picked in this show start its episodes, read once as the item opens.
            val choice = remember { item.showId?.let { prefs.trackChoices[it] } }
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
                skips = prefs.skips,
                seekSteps = prefs.seekSteps,
                subtitleLook = prefs.subtitleLook,
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
            onRetry = page::retry,
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
 * What the player shows for a library item: an episode under its show and number, a track under its artist and
 * album, a movie with its year, its [intro] and [credits], and its [scrub] thumbnails.
 */
private fun MediaItem.vodTitle(intro: IntroSpan?, credits: IntroSpan?, scrub: ScrubTiles?): VodTitle {
    val subtitle = when (kind) {
        ItemKind.EPISODE -> listOfNotNull(showTitle, episodeLabel).joinToString(" · ").ifEmpty { null }
        ItemKind.TRACK -> listOfNotNull(trackArtist ?: artist, album).joinToString(" · ").ifEmpty { null }
        else -> year?.toString()
    }
    val markers = listOfNotNull(
        intro?.let { VodMarker(it.startMs, it.endMs, VodMarkerKind.INTRO) },
        credits?.let { VodMarker(it.startMs, it.endMs, VodMarkerKind.CREDITS) },
    )
    return VodTitle(
        title,
        subtitle,
        artworkUrl = backdrop ?: poster,
        markers = markers,
        scrub = scrub,
    )
}

/** The Up next card for an episode: its number and title under the show, with its still. */
private fun MediaItem.upNext(): VodUpNext = VodUpNext(
    title = title,
    subtitle = listOfNotNull(showTitle, episodeLabel).joinToString(" · ").ifEmpty { null },
    artworkUrl = stillOrBackdrop(),
)
