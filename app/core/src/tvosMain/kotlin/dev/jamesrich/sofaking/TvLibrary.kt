package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.Subtitles
import kotlinx.coroutines.flow.first

/** The media libraries' pages, as the Apple TV app browses them. */
interface TvLibrary {
    fun watchLibraryHome(onChange: (LibraryHomeState) -> Unit): Watch

    /** The grid [openGrid] opened last, as its pages load. */
    fun watchLibraryGrid(onChange: (LibraryGrid?) -> Unit): Watch

    /** [id]'s page, loaded on the first watch. */
    fun watchItem(id: String, onChange: (ItemDetailState) -> Unit): Watch

    fun refreshLibrary()

    fun refreshItem(id: String)

    /** Opens [libraryId]'s grid in [sort] order, only what is not watched with [unwatched], only [genre] when given. */
    fun openGrid(libraryId: String, sort: LibrarySort, unwatched: Boolean, genre: String?)

    fun loadMore()

    fun setWatched(item: MediaItem, watched: Boolean)

    /** [itemId]'s text subtitle track [index], or null when the server cannot give it. */
    @Throws(Exception::class)
    suspend fun subtitles(itemId: String, index: Int): Subtitles?

    /** The episode to offer when [item] ends, or null; see [LibraryViewModel.upNext]. */
    @Throws(Exception::class)
    suspend fun upNext(item: MediaItem): MediaItem?
}

internal class TvLibraryCalls(private val parts: TvParts) : TvLibrary {
    private val library = parts.library

    override fun watchLibraryHome(onChange: (LibraryHomeState) -> Unit): Watch = parts.watch(library.home, onChange)

    override fun watchLibraryGrid(onChange: (LibraryGrid?) -> Unit): Watch = parts.watch(library.grid, onChange)

    override fun watchItem(id: String, onChange: (ItemDetailState) -> Unit): Watch =
        parts.watch(library.detail(id), onChange)

    override fun refreshLibrary() = library.refreshHome()

    override fun refreshItem(id: String) {
        library.detail(id, refresh = true)
    }

    override fun openGrid(libraryId: String, sort: LibrarySort, unwatched: Boolean, genre: String?) =
        library.openGrid(libraryId, sort, unwatched, genre)

    override fun loadMore() = library.loadMore()

    override fun setWatched(item: MediaItem, watched: Boolean) = library.setWatched(item, watched)

    @Throws(Exception::class)
    override suspend fun subtitles(itemId: String, index: Int): Subtitles? = library.subtitles(itemId, index)

    @Throws(Exception::class)
    override suspend fun upNext(item: MediaItem): MediaItem? = library.upNext(item)
}
