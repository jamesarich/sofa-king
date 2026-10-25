package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.WatchlistMarks

/** The household's watchlist, as the Apple TV app's item pages and program sheet show and change it. */
interface TvWatchlist {
    /** What the watchlist holds; its `offered` is false on a server without one. */
    fun watchWatchlist(onChange: (WatchlistMarks) -> Unit): Watch

    fun watchlistHasItem(marks: WatchlistMarks, item: MediaItem): Boolean

    /** Whether [program]'s title is on the watchlist, for any channel or for its own. */
    fun watchlistHasProgram(marks: WatchlistMarks, program: Program): Boolean

    /** Adds [item] to the watchlist, or takes it off, and reloads the library home; false when the server refused. */
    @Throws(Exception::class)
    suspend fun toggleWatchlistItem(item: MediaItem): Boolean

    /** Adds [program]'s title to the watchlist for every channel, or takes it off; false when the server refused. */
    @Throws(Exception::class)
    suspend fun toggleWatchlistTitle(program: Program): Boolean
}

internal class TvWatchlistCalls(private val parts: TvParts) : TvWatchlist {
    private val book = parts.viewModel.watchlist

    override fun watchWatchlist(onChange: (WatchlistMarks) -> Unit): Watch = parts.watch(book.marks, onChange)

    override fun watchlistHasItem(marks: WatchlistMarks, item: MediaItem): Boolean = marks.has(item)

    override fun watchlistHasProgram(marks: WatchlistMarks, program: Program): Boolean = marks.has(program)

    @Throws(Exception::class)
    override suspend fun toggleWatchlistItem(item: MediaItem): Boolean =
        book.toggleItem(item.id).also { if (it) parts.library.refreshHome() }

    @Throws(Exception::class)
    override suspend fun toggleWatchlistTitle(program: Program): Boolean = book.toggleTitle(program)
}
