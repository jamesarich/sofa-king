package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LetterJump
import dev.jamesrich.sofaking.data.LibraryRepository
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaHome
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.SofaKingApi
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.data.WatchNext
import dev.jamesrich.sofaking.data.describe
import dev.jamesrich.sofaking.data.episodeAfter
import dev.jamesrich.sofaking.data.failedLoad
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.data.seasonAfter
import dev.jamesrich.sofaking.data.watchNextItems
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/**
 * The media libraries for every screen: the home shelves, one library's grid, item pages and the household's place
 * in what plays. Playback goes through the app's one [sessions], so playing an item releases the live channel.
 * [apis] is the API client for the server Settings names, null while there is none. [watchNext] gets the Continue
 * watching shelf each time the home loads, from this model's scope, so a stop saved after the app left the screen
 * still reaches the platform's row.
 */
class LibraryViewModel(
    apis: Flow<SofaKingApi?>,
    private val sessions: PlaybackSessions,
    private val newRepository: (SofaKingApi) -> LibraryRepository = ::LibraryRepository,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** How often a playing item's position is saved on the server. */
    private val saveInterval: Duration = 10.seconds,
    /** How often a home with no library is loaded again. */
    private val emptyRecheck: Duration = 5.minutes,
    private val watchNext: WatchNext = WatchNext.None,
) : ViewModel() {
    private var repository: LibraryRepository? = null

    private val _home = MutableStateFlow<LibraryHomeState>(LibraryHomeState.Loading)
    val home: StateFlow<LibraryHomeState> = _home.asStateFlow()

    private val _grid = MutableStateFlow<LibraryGrid?>(null)

    /** The library grid opened last with [openGrid]; it stays loaded while its pages are shown. */
    val grid: StateFlow<LibraryGrid?> = _grid.asStateFlow()

    private val details = HashMap<String, MutableStateFlow<ItemDetailState>>()

    /** The load of each item page, so a newer one cancels the one before it; read and written on the main thread. */
    private val detailJobs = HashMap<String, Job>()
    private var homeJob: Job? = null
    private var gridJob: Job? = null

    private val positions = PositionSaver(timeSource, saveInterval)

    init {
        viewModelScope.launch {
            _home.collect { home -> watchNextItems(home)?.let(watchNext::publish) }
        }
        viewModelScope.launch {
            apis.collect { api ->
                repository = api?.let(newRepository)
                detailJobs.values.forEach { it.cancel() }
                detailJobs.clear()
                details.clear()
                _grid.value = null
                gridJob?.cancel()
                if (api == null) {
                    homeJob?.cancel()
                    _home.value = LibraryHomeState.Unconfigured
                } else {
                    refreshHome()
                }
            }
        }
    }

    /** Reloads the home shelves, keeping the ones on screen until the new ones are in. */
    fun refreshHome() {
        val repository = repository ?: return
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            if (_home.value !is LibraryHomeState.Ready) _home.value = LibraryHomeState.Loading
            while (true) {
                val home = runCatchingCancellable { LibraryHomeState.Ready(repository.home()) }.getOrElse { e ->
                    if (e is SofaKingException && e.statusCode == HttpStatusCode.NotFound) {
                        LibraryHomeState.Unavailable
                    } else {
                        failed(e)
                    }
                }
                _home.value = home
                // The Library tab hides while the server has no library, so ask again until one is added.
                if (home !is LibraryHomeState.Ready || home.home.libraries.isNotEmpty()) break
                delay(emptyRecheck)
            }
        }
    }

    private fun failed(e: Throwable): LibraryHomeState =
        failedLoad("library home", e, _home.value as? LibraryHomeState.Ready, LibraryHomeState::Failed)

    /**
     * Shows [libraryId] in [sort] order, only what is not watched when [unwatched] is set and only [genre] when given.
     * The grid already open with the same choices is kept as it is, so returning from an item lands where the viewer
     * was; one in another order keeps the genres it knows while the new page loads.
     */
    fun openGrid(
        libraryId: String,
        sort: LibrarySort = LibrarySort.TITLE,
        unwatched: Boolean = false,
        genre: String? = null,
    ) {
        val open = _grid.value
        val wanted =
            LibraryGrid(
                libraryId,
                sort,
                unwatched,
                genre,
                genres = open?.takeIf {
                    it.libraryId == libraryId
                }?.genres.orEmpty(),
            )
        // A grid whose next page failed keeps its pages, and loadMore retries that page; only a
        // failed first page loads afresh.
        val sameGrid = open?.takeIf { it.sameQuery(wanted) }
        if (sameGrid != null && (sameGrid.error == null || sameGrid.items.isNotEmpty())) return
        gridJob?.cancel()
        loadTarget = null
        _grid.value = wanted
        loadPage(null)
    }

    /** Loads the grid's next page, or retries the one that failed; nothing while a page loads or all are in. */
    fun loadMore() {
        val grid = _grid.value ?: return
        if (grid.loading && gridJob?.isActive == true) return
        if (grid.next == null && grid.items.isNotEmpty() && grid.error == null) return
        loadPage(grid.next)
    }

    /**
     * Loads pages until the grid holds the item at [offset], for a jump to a letter: as few requests as the server's
     * largest page allows. Nothing when it is in already.
     */
    fun loadThrough(offset: Int) {
        val grid = _grid.value ?: return
        if (grid.items.size > offset || grid.next == null) return
        loadTarget = offset
        if (grid.loading && gridJob?.isActive == true) return
        loadPage(grid.next)
    }

    // The item a letter jump waits for; pages load one after another until it is in.
    private var loadTarget: Int? = null

    private fun loadPage(cursor: String?) {
        val repository = repository ?: return
        val grid = _grid.value ?: return
        _grid.value = grid.copy(loading = true, error = null)
        gridJob = viewModelScope.launch {
            runCatchingCancellable {
                val target = loadTarget
                val limit =
                    target?.let {
                        (it + 1 - grid.items.size + LibraryRepository.PAGE_SIZE).coerceIn(
                            LibraryRepository.PAGE_SIZE,
                            MAX_PAGE,
                        )
                    }
                        ?: LibraryRepository.PAGE_SIZE
                val page = repository.page(grid.libraryId, grid.sort, grid.unwatched, cursor, limit, grid.genre)
                _grid.update { current ->
                    if (current == null || !current.sameQuery(grid)) return@update current
                    // A page can repeat an item that moved between requests; the first place it showed wins.
                    val items = (current.items + page.items).distinctBy { it.id }
                    current.copy(
                        items = items,
                        genres = page.genres,
                        letters = page.letters ?: current.letters,
                        total = page.total,
                        next = page.next,
                        loading = false,
                    )
                }
                val loaded = _grid.value
                if (target != null && loaded != null && loaded.sameQuery(grid)) {
                    if (loaded.items.size > target || loaded.next == null) loadTarget = null else loadPage(loaded.next)
                }
            }.onFailure { e ->
                log.w { "library page failed: ${e.describe()}" }
                _grid.update { it?.copy(loading = false, error = e.describe()) }
            }
        }
    }

    /** [id]'s page, loaded the first time it is asked for and again with [refresh]. */
    fun detail(id: String, refresh: Boolean = false): StateFlow<ItemDetailState> {
        val state = details.getOrPut(id) { MutableStateFlow(ItemDetailState.Loading) }
        if (refresh || state.value !is ItemDetailState.Ready) loadDetail(id, state)
        return state.asStateFlow()
    }

    private fun loadDetail(id: String, state: MutableStateFlow<ItemDetailState>) {
        val repository = repository ?: return
        detailJobs[id]?.cancel()
        detailJobs[id] = viewModelScope.launch {
            runCatchingCancellable {
                state.value = ItemDetailState.Ready(repository.detail(id))
            }.onFailure { e ->
                log.w { "library item failed: ${e.describe()}" }
                // A page on screen stays when a refresh fails.
                if (state.value !is ItemDetailState.Ready) state.value = ItemDetailState.Failed(e.describe())
            }
        }
    }

    /** Items whose title, show or cast match [query]; empty when the server cannot be reached. */
    suspend fun search(query: String): List<MediaItem> {
        val repository = repository ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return runCatchingCancellable {
            repository.search(query.trim())
        }.getOrElse { e ->
            log.w { "library search failed: ${e.describe()}" }
            emptyList()
        }
    }

    /** Marks [item] watched or not; a show or season marks every episode in it. The screens that show it reload. */
    fun setWatched(item: MediaItem, watched: Boolean) {
        val repository = repository ?: return
        viewModelScope.launch {
            runCatchingCancellable {
                changed(repository.setWatched(item.id, watched))
            }.onFailure { e ->
                log.w { "mark watched failed: ${e.describe()}" }
            }
        }
    }

    /**
     * Opens [item] on the app's one session, releasing whatever was held: from the start with [fromStart], else
     * where the household stopped in it. [profile] is what the player plays as it is.
     */
    suspend fun play(
        item: MediaItem,
        profile: PlaybackProfile,
        fromStart: Boolean = false,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
    ): PlaybackStream {
        val start = item.progress?.takeIf { !fromStart && it.inProgress }?.positionMs ?: 0
        positions.reset(item.id)
        return sessions.play(item.id, profile, start, audioIndex, subtitleIndex)
    }

    /**
     * The episode to offer when [item] ends: the next in its season, else the first of the next season. Null for
     * anything but an episode, after a show's last episode, or when the server cannot be reached.
     */
    suspend fun upNext(item: MediaItem): MediaItem? {
        val repository = repository
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
    ): PlaybackStream = sessions.play(item.id, profile, positionMs, audioIndex, subtitleIndex)

    /** [itemId]'s text subtitle track [index], or null when the server cannot give it. */
    suspend fun subtitles(itemId: String, index: Int): Subtitles? {
        val repository = repository ?: return null
        return runCatchingCancellable {
            repository.subtitles(itemId, index)
        }.getOrElse { e ->
            log.w { "subtitles failed: ${e.describe()}" }
            null
        }
    }

    /**
     * The player is at [positionMs] in [itemId]: remembered for a reopen, and saved on the server every
     * [saveInterval] while it plays.
     */
    fun progress(itemId: String, positionMs: Long, durationMs: Long?) {
        sessions.reportPosition(positionMs)
        if (positions.due(itemId)) save(itemId, positionMs, durationMs, refresh = false)
    }

    /** Playback of [itemId] stopped at [positionMs]: saved now, and the home and the item's pages reload. */
    fun stopped(itemId: String, positionMs: Long, durationMs: Long?) {
        sessions.reportPosition(positionMs)
        positions.reset(itemId)
        save(itemId, positionMs, durationMs, refresh = true)
    }

    private fun save(itemId: String, positionMs: Long, durationMs: Long?, refresh: Boolean) {
        val repository = repository ?: return
        viewModelScope.launch {
            runCatchingCancellable {
                val item = repository.savePosition(itemId, positionMs, durationMs)
                if (refresh) changed(item)
            }.onFailure { e ->
                log.w { "saving the position failed: ${e.describe()}" }
            }
        }
    }

    /** [item]'s watch state changed: its place in the grid, the pages it shows on and the home follow. */
    private fun changed(item: MediaItem) {
        _grid.update { grid -> grid?.copy(items = grid.items.map { if (it.id == item.id) item else it }) }
        listOfNotNull(item.id, item.parentId, item.showId).distinct().forEach { id ->
            details[id]?.let { loadDetail(id, it) }
        }
        // A show's grid card counts unwatched episodes; it is reloaded with its page.
        item.showId?.let { show -> if (_grid.value?.items?.any { it.id == show } == true) refreshGridItem(show) }
        refreshHome()
    }

    private fun refreshGridItem(id: String) {
        val repository = repository ?: return
        viewModelScope.launch {
            try {
                val fresh = repository.detail(id).item
                _grid.update { grid -> grid?.copy(items = grid.items.map { if (it.id == id) fresh else it }) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        /** The most items the server sends in one page. */
        const val MAX_PAGE = 500
        val log = Logger.withTag("SofaKing")
    }
}

private fun LibraryGrid.sameQuery(other: LibraryGrid) =
    libraryId == other.libraryId && sort == other.sort && unwatched == other.unwatched && genre == other.genre
