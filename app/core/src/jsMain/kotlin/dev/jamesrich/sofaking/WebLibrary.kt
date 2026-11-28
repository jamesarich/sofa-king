package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.TrackChoice
import dev.jamesrich.sofaking.data.defaultAudio
import dev.jamesrich.sofaking.data.initialAudio
import dev.jamesrich.sofaking.data.initialSubtitle
import dev.jamesrich.sofaking.data.isPictureSubtitle
import dev.jamesrich.sofaking.data.resumes
import dev.jamesrich.sofaking.data.spotlightItem
import dev.jamesrich.sofaking.data.spotlightTagline
import dev.jamesrich.sofaking.data.trackChoice
import dev.jamesrich.sofaking.data.trackLabel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.js.Promise
import kotlin.time.Duration.Companion.seconds

/** The media libraries' pages, as the page browses them. */
@JsExport
class WebLibrary internal constructor(private val parts: WebParts) {
    private val library = parts.library

    fun watchHome(onChange: (WebHome) -> Unit): Watch = parts.watch(
        library.home.map { homeToWeb(it, { item -> parts.toWeb(item) }) { shelf -> parts.toWeb(shelf) } },
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
                    it.letters.map { jump -> WebLetter(jump.letter, jump.offset) }.toTypedArray(),
                    it.total,
                    it.next != null,
                    it.loading,
                    it.error,
                    it.collections.toTypedArray(),
                )
            }
        },
        onChange,
    )

    /** [id]'s page, loaded on the first watch and held open until the watch is canceled. */
    fun watchItem(id: String, onChange: (WebItemState) -> Unit): Watch = parts.watch(
        flow { library.openItem(id).use { emitAll(it.state) } }.combine(library.watchlistMarks) { state, marks ->
            when (state) {
                ItemDetailState.Loading -> WebItemState("loading", "", null)

                is ItemDetailState.Failed -> WebItemState("failed", state.message, null)

                is ItemDetailState.Ready ->
                    WebItemState("ready", "", parts.toWeb(state.detail), marks.listing(state.detail.item))
            }
        },
        onChange,
    )

    fun refreshHome() = library.refreshHome()

    /**
     * Opens [libraryId]'s grid in [sort] order (`title`, `added`, `year`, `rating` or `watched`), with only [genre]
     * and only the movies of [collection] when given.
     */
    fun openGrid(libraryId: String, sort: String, unwatched: Boolean, genre: String?, collection: String?) {
        val order = LibrarySort.entries.firstOrNull { it.name.equals(sort, true) } ?: LibrarySort.TITLE
        library.openGrid(libraryId, order, unwatched, genre, collection = collection)
    }

    fun loadMore() = library.loadMore()

    /** Loads pages until the grid holds the item at [offset], for a jump to a letter. */
    fun loadThrough(offset: Int) = library.loadThrough(offset)

    fun setWatched(itemId: String, watched: Boolean) = library.setWatched(parts.item(itemId), watched)

    /** Adds [itemId] to the household's watchlist, or takes it off; its page's `listed` follows. */
    fun toggleWatchlist(itemId: String) = library.toggleWatchlist(parts.item(itemId))
}

internal fun homeToWeb(
    state: LibraryHomeState,
    item: (MediaItem) -> WebItem,
    shelf: (MediaShelf) -> WebShelf,
): WebHome = when (state) {
    LibraryHomeState.Loading -> WebHome("loading", "", emptyArray(), emptyArray())

    LibraryHomeState.Unconfigured -> WebHome("unconfigured", "", emptyArray(), emptyArray())

    LibraryHomeState.Unavailable -> WebHome("unavailable", "", emptyArray(), emptyArray())

    is LibraryHomeState.Failed -> WebHome("failed", state.message, emptyArray(), emptyArray(), state.signedOut)

    is LibraryHomeState.Ready -> WebHome(
        "ready",
        "",
        state.home.shelves.map(shelf).toTypedArray(),
        state.home.libraries
            .map { WebLibraryInfo(it.id, it.name, it.kind.name.lowercase(), it.itemCount) }
            .toTypedArray(),
        spotlight = spotlightItem(state.home.shelves, focusedId = null)?.let {
            WebSpotlight(item(it), spotlightTagline(it), resumes(it))
        },
    )
}

/** How long the player waits for an item's tracks before it opens without them. */
private val TRACKS_WAIT = 5.seconds

/** A library item on the session, as the page's player plays it. */
@JsExport
class WebItemPlayback internal constructor(private val parts: WebParts) {
    private val library = parts.library
    private val viewModel = parts.viewModel

    /** The item [tracks] read last, which names the show a pick is kept for. */
    private var read: MediaItemDetail? = null

    /**
     * [itemId]'s audio and subtitle tracks and the ones it starts with: the languages picked in its show on this
     * browser, else the file's own. Null when the server does not give the item within [TRACKS_WAIT], so the player
     * opens without a picker rather than waiting on it.
     */
    fun tracks(itemId: String): Promise<WebTracks?> = parts.scope.promise {
        val state = withTimeoutOrNull(TRACKS_WAIT) {
            library.openItem(itemId).use { page -> page.state.first { it !is ItemDetailState.Loading } }
        }
        val detail = (state as? ItemDetailState.Ready)?.detail ?: return@promise null
        read = detail
        parts.remember(detail.item)
        tracksToWeb(detail, detail.item.showId?.let { viewModel.settings.value?.trackChoices?.get(it) })
    }

    /**
     * Opens [itemId] on the session, from the start with [fromStart], else where the household stopped in it, with
     * the audio track [audioIndex] and the picture subtitle track [subtitleIndex] drawn in (the file's own audio and
     * no subtitles when null).
     */
    fun play(
        itemId: String,
        fromStart: Boolean,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
    ): Promise<WebStream> = parts.scope.promise {
        parts.opening {
            library.play(parts.item(itemId), webProfile, fromStart, audioIndex, subtitleIndex).toWeb()
        }
    }

    /** Plays [itemId] again from [positionMs] with the audio track [audioIndex] and [subtitleIndex] drawn in. */
    fun switchTracks(itemId: String, positionMs: Double, audioIndex: Int?, subtitleIndex: Int?): Promise<WebStream> =
        parts.scope.promise {
            parts.opening {
                library.switchTracks(parts.item(itemId), webProfile, positionMs.toLong(), audioIndex, subtitleIndex)
                    .toWeb()
            }
        }

    /** The viewer picked [audioIndex] and [subtitleIndex] (null for off) in [itemId]; its show keeps the languages. */
    fun picked(itemId: String, audioIndex: Int?, subtitleIndex: Int?) {
        val detail = read?.takeIf { it.item.id == itemId } ?: return
        detail.item.showId?.let { viewModel.setTrackChoice(it, trackChoice(detail, audioIndex, subtitleIndex)) }
    }

    /** [itemId]'s text subtitle track [index] as cues, or null when the server cannot give it. */
    fun subtitles(itemId: String, index: Int): Promise<Array<WebCue>?> = parts.scope.promise {
        library.subtitles(itemId, index)?.cues
            ?.map { WebCue(it.startMs.toDouble(), it.endMs.toDouble(), it.text) }
            ?.toTypedArray()
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

/**
 * [detail]'s tracks as the player's picker lists them, starting with the languages [choice] names for its show: the
 * audio track asked of the stream only when it is not the file's own, as the apps do.
 */
internal fun tracksToWeb(detail: MediaItemDetail, choice: TrackChoice?): WebTracks {
    val audio = initialAudio(detail, choice) ?: defaultAudio(detail)
    return WebTracks(
        audio = detail.audio.mapIndexed { i, track -> WebTrack(track.index, trackLabel(track, i + 1), false) }
            .toTypedArray(),
        subtitles = detail.subtitles
            .mapIndexed { i, track -> WebTrack(track.index, trackLabel(track, i + 1), isPictureSubtitle(track)) }
            .toTypedArray(),
        audioIndex = audio?.index,
        openAudio = audio?.index?.takeIf { it != defaultAudio(detail)?.index },
        subtitleIndex = initialSubtitle(detail, choice)?.index,
    )
}
