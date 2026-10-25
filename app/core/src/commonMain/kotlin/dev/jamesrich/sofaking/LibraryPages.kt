package dev.jamesrich.sofaking

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.LibraryRepository
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.WatchNext
import dev.jamesrich.sofaking.data.describe
import dev.jamesrich.sofaking.data.failedLoad
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.data.watchNextItems
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

private val log = Logger.withTag("SofaKing")

/** The most items the server sends in one page. */
private const val MAX_PAGE = 500

/**
 * The library's home shelves from [repository], loaded in [scope]; a home with no library is loaded again every
 * [emptyRecheck]. [watchNext] gets the Continue watching shelf each time the home loads, from this scope, so a stop
 * saved after the app left the screen still reaches the platform's row.
 */
internal class LibraryShelves(
    private val scope: CoroutineScope,
    private val emptyRecheck: Duration,
    watchNext: WatchNext,
    present: (LibraryHomeState) -> LibraryHomeState = { it },
    private val repository: () -> LibraryRepository?,
) {
    private val state = ShownState<LibraryHomeState>(LibraryHomeState.Loading, present)

    /** The home as the screens show it. */
    val home: StateFlow<LibraryHomeState> = state.shown
    private var job: Job? = null

    init {
        scope.launch {
            state.loaded.collect { home -> watchNextItems(home)?.let(watchNext::publish) }
        }
    }

    /** Shows the home again, as the way it is shown changed. */
    fun reshow() = state.reshow()

    /** Reloads the home shelves, keeping the ones on screen until the new ones are in. */
    fun refresh() {
        val repository = repository() ?: return
        job?.cancel()
        job = scope.launch {
            if (state.value !is LibraryHomeState.Ready) state.value = LibraryHomeState.Loading
            while (true) {
                val home = runCatchingCancellable { LibraryHomeState.Ready(repository.home()) }.getOrElse { e ->
                    if (e is SofaKingException && e.statusCode == HttpStatusCode.NotFound) {
                        LibraryHomeState.Unavailable
                    } else {
                        failed(e)
                    }
                }
                state.value = home
                // The Library tab hides while the server has no library, so ask again until one is added.
                if (home !is LibraryHomeState.Ready || home.home.libraries.isNotEmpty()) break
                delay(emptyRecheck)
            }
        }
    }

    /** No server to load from. */
    fun unconfigured() {
        job?.cancel()
        state.value = LibraryHomeState.Unconfigured
    }

    private fun failed(e: Throwable): LibraryHomeState =
        failedLoad("library home", e, state.value as? LibraryHomeState.Ready, LibraryHomeState::Failed)
}

/**
 * The library grid opened last, paged in from [repository] in [scope]; it stays loaded while its pages are shown, as
 * [present] shows them.
 */
internal class LibraryGrids(
    private val scope: CoroutineScope,
    present: (LibraryGrid?) -> LibraryGrid? = { it },
    private val repository: () -> LibraryRepository?,
) {
    private val state = ShownState<LibraryGrid?>(null, present)

    /** The grid as the screens show it. */
    val grid: StateFlow<LibraryGrid?> = state.shown

    /** The grid as loaded, every page's items in it. */
    val loaded: StateFlow<LibraryGrid?> = state.loaded
    private var job: Job? = null

    /** Shows the grid again, as the way it is shown changed. */
    fun reshow() = state.reshow()

    // The item a letter jump waits for; pages load one after another until it is in.
    private var loadTarget: Int? = null

    /** As [LibraryBrowsing.openGrid]. */
    fun open(libraryId: String, sort: LibrarySort, unwatched: Boolean, genre: String?) {
        val open = state.value
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
        job?.cancel()
        loadTarget = null
        state.value = wanted
        loadPage(null)
    }

    /** As [LibraryBrowsing.loadMore]. */
    fun loadMore() {
        val grid = state.value ?: return
        if (grid.loading && job?.isActive == true) return
        if (grid.next == null && grid.items.isNotEmpty() && grid.error == null) return
        loadPage(grid.next)
    }

    /** As [LibraryBrowsing.loadThrough]. */
    fun loadThrough(offset: Int) {
        val grid = state.value ?: return
        if (grid.items.size > offset || grid.next == null) return
        loadTarget = offset
        if (grid.loading && job?.isActive == true) return
        loadPage(grid.next)
    }

    private fun loadPage(cursor: String?) {
        val repository = repository() ?: return
        val grid = state.value ?: return
        state.value = grid.copy(loading = true, error = null)
        job = scope.launch {
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
                state.update { current ->
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
                val loaded = state.value
                if (target != null && loaded != null && loaded.sameQuery(grid)) {
                    if (loaded.items.size > target || loaded.next == null) loadTarget = null else loadPage(loaded.next)
                }
            }.onFailure { e ->
                log.w { "library page failed: ${e.describe()}" }
                state.update { it?.copy(loading = false, error = e.describe()) }
            }
        }
    }

    /** Another server: no grid is open. */
    fun clear() {
        state.value = null
        job?.cancel()
    }

    /** [item] takes its own place in the grid. */
    fun replace(item: MediaItem) {
        state.update { grid -> grid?.copy(items = grid.items.map { if (it.id == item.id) item else it }) }
    }

    /** Reloads the card of [id], when the grid shows it. */
    fun refreshCard(id: String) {
        if (state.value?.items?.any { it.id == id } != true) return
        val repository = repository() ?: return
        scope.launch {
            try {
                replace(repository.detail(id).item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * State kept as loaded and published as [present] shows it: [shown] follows every write at once, and [reshow] applies a
 * change in how it is shown, such as kids mode turning on. Written on the main thread only, as the pages are.
 */
internal class ShownState<T>(initial: T, private val present: (T) -> T) {
    private val raw = MutableStateFlow(initial)
    private val presented = MutableStateFlow(present(initial))
    val loaded: StateFlow<T> = raw.asStateFlow()
    val shown: StateFlow<T> = presented.asStateFlow()

    var value: T
        get() = raw.value
        set(value) {
            raw.value = value
            reshow()
        }

    fun update(transform: (T) -> T) {
        raw.update(transform)
        reshow()
    }

    fun reshow() {
        presented.value = present(raw.value)
    }
}

/** Each item's page, loaded from [repository] in [scope] the first time it is asked for. */
internal class ItemPages(private val scope: CoroutineScope, private val repository: () -> LibraryRepository?) {
    private val details = HashMap<String, MutableStateFlow<ItemDetailState>>()

    /** The load of each item page, so a newer one cancels the one before it; read and written on the main thread. */
    private val jobs = HashMap<String, Job>()

    /** As [LibraryBrowsing.detail]. */
    fun detail(id: String, refresh: Boolean): StateFlow<ItemDetailState> {
        val state = details.getOrPut(id) { MutableStateFlow(ItemDetailState.Loading) }
        if (refresh || state.value !is ItemDetailState.Ready) load(id, state)
        return state.asStateFlow()
    }

    /** Reloads the pages of [ids] that were opened. */
    fun reload(ids: Collection<String>) {
        ids.forEach { id -> details[id]?.let { load(id, it) } }
    }

    /** Another server: every page is forgotten. */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        details.clear()
    }

    private fun load(id: String, state: MutableStateFlow<ItemDetailState>) {
        val repository = repository() ?: return
        jobs[id]?.cancel()
        jobs[id] = scope.launch {
            runCatchingCancellable {
                state.value = ItemDetailState.Ready(repository.detail(id))
            }.onFailure { e ->
                log.w { "library item failed: ${e.describe()}" }
                // A page on screen stays when a refresh fails.
                if (state.value !is ItemDetailState.Ready) state.value = ItemDetailState.Failed(e.describe())
            }
        }
    }
}

private fun LibraryGrid.sameQuery(other: LibraryGrid) =
    libraryId == other.libraryId && sort == other.sort && unwatched == other.unwatched && genre == other.genre
