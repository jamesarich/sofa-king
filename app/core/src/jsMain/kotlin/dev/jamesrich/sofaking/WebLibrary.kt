package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.LibrarySort
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.promise
import kotlin.js.Promise

/** The media libraries' pages, as the page browses them. */
@JsExport
class WebLibrary internal constructor(private val parts: WebParts) {
    private val library = parts.library

    fun watchHome(onChange: (WebHome) -> Unit): Watch = parts.watch(
        library.home.map { state ->
            when (state) {
                LibraryHomeState.Loading -> WebHome("loading", "", emptyArray(), emptyArray())

                LibraryHomeState.Unconfigured -> WebHome("unconfigured", "", emptyArray(), emptyArray())

                LibraryHomeState.Unavailable -> WebHome("unavailable", "", emptyArray(), emptyArray())

                is LibraryHomeState.Failed -> WebHome("failed", state.message, emptyArray(), emptyArray())

                is LibraryHomeState.Ready -> WebHome(
                    "ready",
                    "",
                    state.home.shelves.map { parts.toWeb(it) }.toTypedArray(),
                    state.home.libraries
                        .map { WebLibraryInfo(it.id, it.name, it.kind.name.lowercase(), it.itemCount) }
                        .toTypedArray(),
                )
            }
        },
        onChange,
    )

    /** The grid [openGrid] opened last, as its pages load. */
    fun watchGrid(onChange: (WebGrid?) -> Unit): Watch = parts.watch(
        library.grid.map { grid ->
            grid?.let {
                WebGrid(
                    it.libraryId,
                    it.items.toWeb(parts),
                    it.genres.toTypedArray(),
                    it.total,
                    it.next != null,
                    it.loading,
                    it.error,
                )
            }
        },
        onChange,
    )

    /** [id]'s page, loaded on the first watch and held open until the watch is canceled. */
    fun watchItem(id: String, onChange: (WebItemState) -> Unit): Watch = parts.watch(
        flow { library.openItem(id).use { emitAll(it.state) } }.map { state ->
            when (state) {
                ItemDetailState.Loading -> WebItemState("loading", "", null)
                is ItemDetailState.Failed -> WebItemState("failed", state.message, null)
                is ItemDetailState.Ready -> WebItemState("ready", "", parts.toWeb(state.detail))
            }
        },
        onChange,
    )

    fun refreshHome() = library.refreshHome()

    /** Opens [libraryId]'s grid in [sort] order (`title`, `added`, `year`, `rating` or `watched`). */
    fun openGrid(libraryId: String, sort: String, unwatched: Boolean, genre: String?) {
        val order = LibrarySort.entries.firstOrNull { it.name.equals(sort, true) } ?: LibrarySort.TITLE
        library.openGrid(libraryId, order, unwatched, genre)
    }

    fun loadMore() = library.loadMore()

    fun setWatched(itemId: String, watched: Boolean) = library.setWatched(parts.item(itemId), watched)
}

/** A library item on the session, as the page's player plays it. */
@JsExport
class WebItemPlayback internal constructor(private val parts: WebParts) {
    private val library = parts.library
    private val viewModel = parts.viewModel

    /** Opens [itemId] on the session, from the start with [fromStart], else where the household stopped in it. */
    fun play(itemId: String, fromStart: Boolean): Promise<WebStream> = parts.scope.promise {
        parts.opening { library.play(parts.item(itemId), webProfile, fromStart).toWeb() }
    }

    /** Restarts a converted stream at [positionMs] in the item; a file played as it is comes back unchanged. */
    fun restartAt(positionMs: Double): Promise<WebStream?> = parts.scope.promise {
        viewModel.sessions.restartAt(positionMs.toLong())?.toWeb()
    }

    /** The player is at [positionMs] in [itemId]; [durationMs] is 0 while the length is unknown. */
    fun progress(itemId: String, positionMs: Double, durationMs: Double) =
        library.progress(itemId, positionMs.toLong(), durationMs.toLong().takeIf { it > 0 })

    /** Playback of [itemId] stopped at [positionMs]; saved now, and the library's pages reload. */
    fun stopped(itemId: String, positionMs: Double, durationMs: Double) =
        library.stopped(itemId, positionMs.toLong(), durationMs.toLong().takeIf { it > 0 })
}
