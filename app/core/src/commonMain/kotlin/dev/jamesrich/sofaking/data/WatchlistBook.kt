package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * What the household's watchlist holds, as the library's pages and the guide's program details mark it: library items
 * by id and guide titles, each to its entry's id.
 */
data class WatchlistMarks(
    /** Whether the server keeps a watchlist: false until it is read, and on a server without one. */
    val offered: Boolean = false,
    internal val items: Map<String, String> = emptyMap(),
    /** Title entries by [titleKey], one on any channel with no channel in its key. */
    internal val titles: Map<String, String> = emptyMap(),
) {
    fun has(item: MediaItem): Boolean = item.id in items

    /** Whether [program]'s title is on the list, for any channel or for its own. */
    fun has(program: Program): Boolean = titleIds(program).isNotEmpty()

    internal fun titleIds(program: Program): List<String> =
        listOfNotNull(titles[titleKey(program.title, null)], titles[titleKey(program.title, program.channelId)])
}

// The server matches a title ignoring case and outer spaces, as it does a series rule's.
private fun titleKey(title: String, channelId: String?) = title.trim().lowercase() + "@" + channelId.orEmpty()

private fun WatchlistDto.marks() = WatchlistMarks(
    offered = true,
    items = entries.filter { it.kind == Wire.WatchlistKind.ITEM }
        .mapNotNull { entry -> entry.itemId?.let { it to entry.id } }
        .toMap(),
    titles = entries.filter { it.kind == Wire.WatchlistKind.TITLE }.associate {
        titleKey(it.title, it.channelId) to
            it.id
    },
)

/**
 * The household's watchlist on the server [apis] names, read when the server changes on one that keeps it ([enabled])
 * and again after every change made here; [marks] stays empty otherwise.
 */
class WatchlistBook internal constructor(scope: CoroutineScope, apis: Flow<SofaKingApi?>, enabled: Flow<Boolean>) {
    private val _marks = MutableStateFlow(WatchlistMarks())
    val marks: StateFlow<WatchlistMarks> = _marks.asStateFlow()

    @Volatile private var api: SofaKingApi? = null
    private val mutex = Mutex()

    init {
        scope.launch {
            combine(apis, enabled, ::Pair).collectLatest { (found, on) ->
                api = found?.takeIf { on }
                _marks.value = WatchlistMarks()
                refresh()
            }
        }
    }

    /** Reads the list again; a failure keeps the marks shown. */
    suspend fun refresh() = mutex.withLock {
        val api = api ?: return@withLock
        runCatchingCancellable { _marks.value = api.watchlist().marks() }
            .onFailure { e -> log.w { "reading the watchlist failed: ${e.describe()}" } }
    }

    /** Adds the library item [itemId] to the list, or takes it off; false when the server did not take it. */
    suspend fun toggleItem(itemId: String): Boolean = change("changing the watchlist") {
        val id = _marks.value.items[itemId]
        if (id != null) {
            deleteWatchlistEntry(id)
        } else {
            addWatchlistEntry(AddWatchlistEntryDto(Wire.WatchlistKind.ITEM, itemId = itemId))
        }
    }

    /**
     * Adds [program]'s title to the list for every channel, or takes off every entry that holds it. False when the
     * server refused or was unreachable.
     */
    suspend fun toggleTitle(program: Program): Boolean = change("changing the watchlist") {
        val ids = _marks.value.titleIds(program)
        if (ids.isNotEmpty()) {
            ids.forEach { deleteWatchlistEntry(it) }
        } else {
            addWatchlistEntry(AddWatchlistEntryDto(Wire.WatchlistKind.TITLE, title = program.title.trim()))
        }
    }

    /** Runs [action] on the server, then reads the list again whether it worked or not. False when it failed. */
    private suspend fun change(what: String, action: suspend SofaKingApi.() -> Unit): Boolean {
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

    private companion object {
        val log = Logger.withTag("SofaKing")
    }
}

internal suspend fun SofaKingApi.watchlist(): WatchlistDto = call {
    http.get("watchlist").decode()
}

internal suspend fun SofaKingApi.addWatchlistEntry(request: AddWatchlistEntryDto): WatchlistEntryDto = call {
    http.post("watchlist") { json(request) }.decode()
}

internal suspend fun SofaKingApi.deleteWatchlistEntry(id: String) = call {
    http.delete("watchlist/" + id.encodeURLPathPart()).checkSuccess()
}
