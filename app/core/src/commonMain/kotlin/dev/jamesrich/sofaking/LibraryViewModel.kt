package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.KidsMode
import dev.jamesrich.sofaking.data.LetterJump
import dev.jamesrich.sofaking.data.LibraryRepository
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.Lyrics
import dev.jamesrich.sofaking.data.MediaHome
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.MusicView
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.SearchResults
import dev.jamesrich.sofaking.data.SofaKingApi
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.data.WatchNext
import dev.jamesrich.sofaking.data.WatchlistBook
import dev.jamesrich.sofaking.data.WatchlistMarks
import dev.jamesrich.sofaking.data.describe
import dev.jamesrich.sofaking.data.episodeAfter
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.data.seasonAfter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

sealed interface LibraryHomeState {
    data object Loading : LibraryHomeState

    /** No usable server address yet. */
    data object Unconfigured : LibraryHomeState

    /** The server keeps no libraries, or is too old to serve them. */
    data object Unavailable : LibraryHomeState
    data class Ready(val home: MediaHome) : LibraryHomeState
    data class Failed(val message: String) : LibraryHomeState
}

/** One library's grid as far as it is loaded, only [genre] when set; [next] is null once every page is in. */
data class LibraryGrid(
    val libraryId: String,
    val sort: LibrarySort,
    val unwatched: Boolean,
    val genre: String? = null,
    /** What a Music library lists; any other library lists its top items. */
    val view: MusicView = MusicView.ARTISTS,
    val items: List<MediaItem> = emptyList(),
    /** Every genre in the library, from the first page. */
    val genres: List<String> = emptyList(),
    /** Where each first letter starts, in title order; empty in any other order. */
    val letters: List<LetterJump> = emptyList(),
    val total: Int = 0,
    val next: String? = null,
    val loading: Boolean = true,
    /** Why the last page failed; the grid keeps what it has and [LibraryViewModel.loadMore] tries again. */
    val error: String? = null,
)

sealed interface ItemDetailState {
    data object Loading : ItemDetailState
    data class Ready(val detail: MediaItemDetail) : ItemDetailState
    data class Failed(val message: String) : ItemDetailState
}

/** Browsing the libraries: the home shelves, one library's grid, the item pages, search and the watched marks. */
interface LibraryBrowsing {
    val home: StateFlow<LibraryHomeState>

    /** The library grid opened last with [openGrid]; it stays loaded while its pages are shown. */
    val grid: StateFlow<LibraryGrid?>

    /** Reloads the home shelves, keeping the ones on screen until the new ones are in. */
    fun refreshHome()

    /**
     * Shows [libraryId] in [sort] order, only what is not watched when [unwatched] is set and only [genre] when given.
     * The grid already open with the same choices is kept as it is, so returning from an item lands where the viewer
     * was; one in another order keeps the genres it knows while the new page loads. A Music library lists its [view].
     */
    fun openGrid(
        libraryId: String,
        sort: LibrarySort = LibrarySort.TITLE,
        unwatched: Boolean = false,
        genre: String? = null,
        view: MusicView = MusicView.ARTISTS,
    )

    /** Loads the grid's next page, or retries the one that failed; nothing while a page loads or all are in. */
    fun loadMore()

    /**
     * Loads pages until the grid holds the item at [offset], for a jump to a letter: as few requests as the server's
     * largest page allows. Nothing when it is in already.
     */
    fun loadThrough(offset: Int)

    /**
     * Opens [id]'s page, which stays loaded and follows changes to what was watched until the [ItemPage] is closed. A
     * screen holds one for as long as it is on the back stack.
     */
    fun openItem(id: String): ItemPage

    /** Items whose title, show or cast match [query]; none when the server cannot be reached. */
    suspend fun search(query: String): SearchResults

    /** Marks [item] watched or not; a show or season marks every episode in it. The screens that show it reload. */
    fun setWatched(item: MediaItem, watched: Boolean)

    /** Takes [item] off Continue watching until it is played again; the home reloads without it. */
    fun removeFromContinue(item: MediaItem)
}

/** Playing library items: opening one, what comes next, its tracks and subtitles, and the household's place in it. */
interface LibraryPlayback {
    /**
     * Opens [item] on the app's one session, releasing whatever was held: at [startMs] when a link names it, from the
     * start with [fromStart], else where the household stopped in it. [profile] is what the player plays as it is.
     */
    suspend fun play(
        item: MediaItem,
        profile: PlaybackProfile,
        fromStart: Boolean = false,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
        startMs: Long? = null,
    ): PlaybackStream

    /**
     * The episode to offer when [item] ends: the next in its season, else the first of the next season. Null for
     * anything but an episode, after a show's last episode, or when the server cannot be reached.
     */
    suspend fun upNext(item: MediaItem): MediaItem?

    /**
     * Plays [item] again from [positionMs] with the audio track [audioIndex] and the picture subtitle track
     * [subtitleIndex] drawn in (none when null), on the same terms as [play]; the player loads the new stream the
     * sessions publish.
     */
    suspend fun switchTracks(
        item: MediaItem,
        profile: PlaybackProfile,
        positionMs: Long,
        audioIndex: Int?,
        subtitleIndex: Int?,
    ): PlaybackStream

    /** [itemId]'s text subtitle track [index], or null when the server cannot give it. */
    suspend fun subtitles(itemId: String, index: Int): Subtitles?

    /** The song [itemId]'s lyrics, or null when it has none or the server cannot give them. */
    suspend fun lyrics(itemId: String): Lyrics? = null

    /**
     * The player is at [positionMs] in [itemId]: remembered for a reopen, and saved on the server every save
     * interval while it plays.
     */
    fun progress(itemId: String, positionMs: Long, durationMs: Long?)

    /** Playback of [itemId] stopped at [positionMs]: saved now, and the home and the item's pages reload. */
    fun stopped(itemId: String, positionMs: Long, durationMs: Long?)

    /** The song [itemId] played far enough to count: one more play for Recently played, Most played and the radio. */
    fun played(itemId: String) = Unit

    /** Likes or unlikes the song, album or artist [itemId] for the household. */
    fun setFavorite(itemId: String, favorite: Boolean) = Unit
}

/**
 * The media libraries for every screen: the home shelves, one library's grid, item pages and the household's place
 * in what plays. Playback goes through the app's one sessions, so playing an item releases the live channel.
 */
class LibraryViewModel private constructor(parts: LibraryParts, private val book: WatchlistBook?) :
    ViewModel(parts.scope),
    LibraryBrowsing by parts.browsing,
    LibraryPlayback by parts.playback {
    /**
     * The libraries of the server [apis] names, null while there is none, played through [sessions]. A playing item's
     * position is saved every [saveInterval], a home with no library is loaded again every [emptyRecheck], and
     * [watchNext] gets the Continue watching shelf each time the home loads, from this model's scope, so a stop saved
     * after the app left the screen still reaches the platform's row. [watchlist] is the household's watchlist, which
     * the item pages add to. While [kidsMode] is on, the home, the grid and search show only what [KidsMode] allows.
     */
    constructor(
        apis: Flow<SofaKingApi?>,
        sessions: PlaybackSessions,
        newRepository: (SofaKingApi) -> LibraryRepository = ::LibraryRepository,
        timeSource: TimeSource = TimeSource.Monotonic,
        saveInterval: Duration = 10.seconds,
        emptyRecheck: Duration = 5.minutes,
        watchNext: WatchNext = WatchNext.None,
        watchlist: WatchlistBook? = null,
        kidsMode: Flow<Boolean> = flowOf(false),
    ) : this(
        LibraryParts(
            apis,
            sessions,
            newRepository,
            PositionSaver(timeSource, saveInterval),
            emptyRecheck,
            watchNext,
            kidsMode,
        ),
        watchlist,
    )

    /** What the household's watchlist holds; empty on a server without one. */
    val watchlistMarks: StateFlow<WatchlistMarks> get() = book?.marks ?: noMarks

    /** Adds [item] to the household's watchlist or takes it off; the home reloads, as its Watchlist shelf follows. */
    fun toggleWatchlist(item: MediaItem) {
        val watchlist = book ?: return
        viewModelScope.launch { if (watchlist.toggleItem(item.id)) refreshHome() }
    }
}

private val noMarks: StateFlow<WatchlistMarks> = MutableStateFlow(WatchlistMarks())

/**
 * What a [LibraryViewModel] is made of, in its main-thread [scope]: the repository for the server [apis] names, the
 * shelves, grid and pages it browses, and the player's side. While [kidsMode] is on, the shelves, the grid and search
 * show only what [KidsMode] allows.
 */
internal class LibraryParts(
    apis: Flow<SofaKingApi?>,
    sessions: PlaybackSessions,
    newRepository: (SofaKingApi) -> LibraryRepository,
    positions: PositionSaver,
    emptyRecheck: Duration,
    watchNext: WatchNext,
    kidsMode: Flow<Boolean>,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val kids = kidsMode.stateIn(scope, SharingStarted.Eagerly, false)
    private var repository: LibraryRepository? = null
    private val shelves = LibraryShelves(scope, emptyRecheck, watchNext, { kidsHome(it, kids.value) }) { repository }
    private val grids = LibraryGrids(scope, { grid -> grid?.let { kidsGrid(it, kids.value) } }) { repository }
    private val pages = ItemPages(scope) { repository }
    val browsing = LibraryBrowser(shelves, grids, pages, scope, kids::value) { repository }
    val playback = LibraryPlayer(sessions, positions, scope, { repository }, browsing::changed)

    init {
        scope.launch {
            apis.collect { api ->
                repository = api?.let(newRepository)
                pages.reset()
                grids.clear()
                if (api == null) shelves.unconfigured() else shelves.refresh()
            }
        }
        scope.launch {
            kids.collect {
                shelves.reshow()
                grids.reshow()
            }
        }
        // Kids mode leaves some pages thin, so the next loads until the grid fills a screen or the library ends.
        scope.launch {
            combine(grids.loaded, kids) { grid, kids -> kids && grid != null && kidsGridThin(grid) }
                .collect { thin -> if (thin) grids.loadMore() }
        }
    }
}

/**
 * [LibraryBrowsing] over the [shelves], [grids] and [pages], with [repository] for search and the watched marks; search
 * finds only what [KidsMode] allows while [kids] says so.
 */
internal class LibraryBrowser(
    private val shelves: LibraryShelves,
    private val grids: LibraryGrids,
    private val pages: ItemPages,
    private val scope: CoroutineScope,
    private val kids: () -> Boolean,
    private val repository: () -> LibraryRepository?,
) : LibraryBrowsing {
    override val home: StateFlow<LibraryHomeState> get() = shelves.home
    override val grid: StateFlow<LibraryGrid?> get() = grids.grid

    override fun refreshHome() = shelves.refresh()

    override fun openGrid(libraryId: String, sort: LibrarySort, unwatched: Boolean, genre: String?, view: MusicView) =
        grids.open(libraryId, sort, unwatched, genre, view)

    override fun loadMore() = grids.loadMore()

    override fun loadThrough(offset: Int) = grids.loadThrough(offset)

    override fun openItem(id: String): ItemPage = pages.open(id)

    override suspend fun search(query: String): SearchResults {
        val repository = repository() ?: return SearchResults.None
        if (query.isBlank()) return SearchResults.None
        return runCatchingCancellable {
            repository.search(query.trim()).let { found ->
                if (kids()) found.copy(items = found.items.filter(KidsMode::allows)) else found
            }
        }.getOrElse { e ->
            log.w { "library search failed: ${e.describe()}" }
            SearchResults.None
        }
    }

    override fun setWatched(item: MediaItem, watched: Boolean) {
        val repository = repository() ?: return
        scope.launch {
            runCatchingCancellable {
                changed(repository.setWatched(item.id, watched))
            }.onFailure { e ->
                log.w { "mark watched failed: ${e.describe()}" }
            }
        }
    }

    override fun removeFromContinue(item: MediaItem) {
        val repository = repository() ?: return
        scope.launch {
            runCatchingCancellable {
                repository.removeFromContinue(item.id)
                shelves.refresh()
            }.onFailure { e ->
                log.w { "remove from Continue watching failed: ${e.describe()}" }
            }
        }
    }

    /** [item]'s watch state changed: its place in the grid, the pages it shows on and the home follow. */
    fun changed(item: MediaItem) {
        grids.replace(item)
        pages.reload(listOfNotNull(item.id, item.parentId, item.showId).distinct())
        // A show's grid card counts unwatched episodes; it is reloaded with its page.
        item.showId?.let(grids::refreshCard)
        shelves.refresh()
    }
}

/**
 * [LibraryPlayback] on the app's one [sessions]: positions are saved through [repository] when [positions] says, and
 * a stop tells [onChanged] about the item it saved.
 */
internal class LibraryPlayer(
    private val sessions: PlaybackSessions,
    private val positions: PositionSaver,
    private val scope: CoroutineScope,
    private val repository: () -> LibraryRepository?,
    private val onChanged: (MediaItem) -> Unit,
) : LibraryPlayback {
    override suspend fun play(
        item: MediaItem,
        profile: PlaybackProfile,
        fromStart: Boolean,
        audioIndex: Int?,
        subtitleIndex: Int?,
        startMs: Long?,
    ): PlaybackStream {
        val start = startMs ?: item.progress?.takeIf { !fromStart && it.inProgress }?.positionMs ?: 0
        positions.reset(item.id)
        return sessions.play(item.id, profile, start, audioIndex, subtitleIndex)
    }

    override suspend fun upNext(item: MediaItem): MediaItem? {
        val repository = repository()
        val parent = item.parentId
        if (repository == null || parent == null || item.kind != ItemKind.EPISODE) return null
        return runCatchingCancellable {
            episodeAfter(repository.detail(parent).children, item.id)
                ?: item.showId?.takeIf { it != parent }
                    ?.let { show -> seasonAfter(repository.detail(show).children, parent) }
                    ?.let { season -> repository.detail(season.id).children.firstOrNull { it.kind.playable } }
        }.getOrElse { e ->
            log.w { "up next failed: ${e.describe()}" }
            null
        }
    }

    override suspend fun switchTracks(
        item: MediaItem,
        profile: PlaybackProfile,
        positionMs: Long,
        audioIndex: Int?,
        subtitleIndex: Int?,
    ): PlaybackStream = sessions.play(item.id, profile, positionMs, audioIndex, subtitleIndex)

    override suspend fun lyrics(itemId: String): Lyrics? {
        val repository = repository() ?: return null
        return runCatchingCancellable { repository.lyrics(itemId) }.getOrElse { e ->
            log.w { "lyrics failed: ${e.describe()}" }
            null
        }
    }

    override suspend fun subtitles(itemId: String, index: Int): Subtitles? {
        val repository = repository() ?: return null
        return runCatchingCancellable {
            repository.subtitles(itemId, index)
        }.getOrElse { e ->
            log.w { "subtitles failed: ${e.describe()}" }
            null
        }
    }

    override fun progress(itemId: String, positionMs: Long, durationMs: Long?) {
        sessions.reportPosition(positionMs)
        if (positions.due(itemId)) save(itemId, positionMs, durationMs, refresh = false)
    }

    override fun stopped(itemId: String, positionMs: Long, durationMs: Long?) {
        sessions.reportPosition(positionMs)
        positions.reset(itemId)
        save(itemId, positionMs, durationMs, refresh = true)
    }

    override fun played(itemId: String) {
        val repository = repository() ?: return
        scope.launch {
            runCatchingCancellable { repository.recordPlay(itemId) }.onFailure { e ->
                log.w { "counting a play failed: ${e.describe()}" }
            }
        }
    }

    override fun setFavorite(itemId: String, favorite: Boolean) {
        val repository = repository() ?: return
        scope.launch {
            runCatchingCancellable { repository.setFavorite(itemId, favorite) }.onFailure { e ->
                log.w { "liking failed: ${e.describe()}" }
            }
        }
    }

    private fun save(itemId: String, positionMs: Long, durationMs: Long?, refresh: Boolean) {
        val repository = repository() ?: return
        scope.launch {
            runCatchingCancellable {
                val item = repository.savePosition(itemId, positionMs, durationMs)
                if (refresh) onChanged(item)
            }.onFailure { e ->
                log.w { "saving the position failed: ${e.describe()}" }
            }
        }
    }
}

/** [home] as the screens show it: every shelf down to the titles kids mode shows while [kids] is on. */
private fun kidsHome(home: LibraryHomeState, kids: Boolean): LibraryHomeState =
    if (kids && home is LibraryHomeState.Ready) LibraryHomeState.Ready(KidsMode.home(home.home)) else home

/**
 * [grid] as the screens show it: down to the titles kids mode shows while [kids] is on. Its letters then go, since they
 * jump to places in the whole library, and its total counts what is shown so far.
 */
private fun kidsGrid(grid: LibraryGrid, kids: Boolean): LibraryGrid {
    if (!kids) return grid
    val items = grid.items.filter(KidsMode::allows)
    return grid.copy(items = items, letters = emptyList(), total = items.size)
}

/** Whether a kids mode [grid], as loaded, shows fewer titles than fill a screen and has more pages to load. */
private fun kidsGridThin(grid: LibraryGrid): Boolean =
    !grid.loading && grid.next != null && grid.items.count(KidsMode::allows) < KIDS_GRID_FILL

/** How many titles kids mode loads into a grid before it waits for the viewer to scroll. */
private const val KIDS_GRID_FILL = 24

private val log = Logger.withTag("SofaKing")
