package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
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

/** One library's grid as far as it is loaded; [next] is null once every page is in. */
data class LibraryGrid(
    val libraryId: String,
    val sort: LibrarySort,
    val unwatched: Boolean,
    val items: List<MediaItem> = emptyList(),
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
 * [apis] is the API client for the server Settings names, null while there is none.
 */
class LibraryViewModel(
    apis: Flow<SofaKingApi?>,
    private val sessions: PlaybackSessions,
    private val newRepository: (SofaKingApi) -> LibraryRepository = ::LibraryRepository,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** How often a playing item's position is saved on the server. */
    private val saveInterval: Duration = 10.seconds,
) : ViewModel() {
    private var repository: LibraryRepository? = null

    private val _home = MutableStateFlow<LibraryHomeState>(LibraryHomeState.Loading)
    val home: StateFlow<LibraryHomeState> = _home.asStateFlow()

    private val _grid = MutableStateFlow<LibraryGrid?>(null)

    /** The library grid opened last with [openGrid]; it stays loaded while its pages are shown. */
    val grid: StateFlow<LibraryGrid?> = _grid.asStateFlow()

    private val details = HashMap<String, MutableStateFlow<ItemDetailState>>()
    private var homeJob: Job? = null
    private var gridJob: Job? = null

    /** When the playing item's position was last saved, by item; read and written on the main thread. */
    private val saved = HashMap<String, TimeMark>()

    init {
        viewModelScope.launch {
            apis.collect { api ->
                repository = api?.let(newRepository)
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
            _home.value = try {
                LibraryHomeState.Ready(repository.home())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SofaKingException) {
                if (e.status == 404) LibraryHomeState.Unavailable else failed(e)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    private fun failed(e: Exception): LibraryHomeState {
        log.w { "library home failed: ${describe(e)}" }
        return (_home.value as? LibraryHomeState.Ready) ?: LibraryHomeState.Failed(describe(e))
    }

    /**
     * Shows [libraryId] in [sort] order, only what is not watched when [unwatched] is set. The grid already open
     * with the same choices is kept as it is, so returning from an item lands where the viewer was.
     */
    fun openGrid(libraryId: String, sort: LibrarySort = LibrarySort.TITLE, unwatched: Boolean = false) {
        val open = _grid.value
        if (open != null && open.libraryId == libraryId && open.sort == sort && open.unwatched == unwatched && open.error == null) return
        gridJob?.cancel()
        _grid.value = LibraryGrid(libraryId, sort, unwatched)
        loadPage(null)
    }

    /** Loads the grid's next page, or retries the one that failed; nothing while a page loads or all are in. */
    fun loadMore() {
        val grid = _grid.value ?: return
        if (grid.loading && gridJob?.isActive == true) return
        if (grid.next == null && grid.items.isNotEmpty() && grid.error == null) return
        loadPage(grid.next)
    }

    private fun loadPage(cursor: String?) {
        val repository = repository ?: return
        val grid = _grid.value ?: return
        _grid.value = grid.copy(loading = true, error = null)
        gridJob = viewModelScope.launch {
            try {
                val page = repository.page(grid.libraryId, grid.sort, grid.unwatched, cursor)
                _grid.update { current ->
                    if (current == null || current.libraryId != grid.libraryId || current.sort != grid.sort || current.unwatched != grid.unwatched) return@update current
                    // A page can repeat an item that moved between requests; the first place it showed wins.
                    val items = (current.items + page.items).distinctBy { it.id }
                    current.copy(items = items, total = page.total, next = page.next, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "library page failed: ${describe(e)}" }
                _grid.update { it?.copy(loading = false, error = describe(e)) }
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
        viewModelScope.launch {
            try {
                state.value = ItemDetailState.Ready(repository.detail(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "library item failed: ${describe(e)}" }
                // A page on screen stays when a refresh fails.
                if (state.value !is ItemDetailState.Ready) state.value = ItemDetailState.Failed(describe(e))
            }
        }
    }

    /** Items whose title, show or cast match [query]; empty when the server cannot be reached. */
    suspend fun search(query: String): List<MediaItem> {
        val repository = repository ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return try {
            repository.search(query.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "library search failed: ${describe(e)}" }
            emptyList()
        }
    }

    /** Marks [item] watched or not; a show or season marks every episode in it. The screens that show it reload. */
    fun setWatched(item: MediaItem, watched: Boolean) {
        val repository = repository ?: return
        viewModelScope.launch {
            try {
                changed(repository.setWatched(item.id, watched))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "mark watched failed: ${describe(e)}" }
            }
        }
    }

    /**
     * Opens [item] on the app's one session, releasing whatever was held: from the start with [fromStart], else
     * where the household stopped in it. [profile] is what the player plays as it is.
     */
    suspend fun play(item: MediaItem, profile: PlaybackProfile, fromStart: Boolean = false, audioIndex: Int? = null): PlaybackStream {
        val start = item.progress?.takeIf { !fromStart && it.inProgress }?.positionMs ?: 0
        saved.remove(item.id)
        return sessions.play(item.id, profile, start, audioIndex)
    }

    /**
     * The player is at [positionMs] in [itemId]: remembered for a reopen, and saved on the server every
     * [saveInterval] while it plays.
     */
    fun progress(itemId: String, positionMs: Long, durationMs: Long?) {
        sessions.reportPosition(positionMs)
        val last = saved[itemId]
        // The first report starts the interval; a few seconds in is not worth a write.
        if (last != null && last.elapsedNow() < saveInterval) return
        saved[itemId] = timeSource.markNow()
        if (last == null) return
        save(itemId, positionMs, durationMs, refresh = false)
    }

    /** Playback of [itemId] stopped at [positionMs]: saved now, and the home and the item's pages reload. */
    fun stopped(itemId: String, positionMs: Long, durationMs: Long?) {
        sessions.reportPosition(positionMs)
        saved.remove(itemId)
        save(itemId, positionMs, durationMs, refresh = true)
    }

    private fun save(itemId: String, positionMs: Long, durationMs: Long?, refresh: Boolean) {
        val repository = repository ?: return
        viewModelScope.launch {
            try {
                val item = repository.savePosition(itemId, positionMs, durationMs)
                if (refresh) changed(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "saving the position failed: ${describe(e)}" }
            }
        }
    }

    /** [item]'s watch state changed: its place in the grid, the pages it shows on and the home follow. */
    private fun changed(item: MediaItem) {
        _grid.update { grid -> grid?.copy(items = grid.items.map { if (it.id == item.id) item else it }) }
        listOfNotNull(item.id, item.parentId, item.showId).distinct().forEach { id -> details[id]?.let { loadDetail(id, it) } }
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

    private fun describe(failure: Throwable): String = when (failure) {
        is SofaKingException -> "${failure.code}: ${failure.message}"
        else -> failure.message ?: failure.toString()
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
    }
}
