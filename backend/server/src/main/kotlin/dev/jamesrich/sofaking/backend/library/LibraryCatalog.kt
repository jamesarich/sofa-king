package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.hash
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraries
import dev.jamesrich.sofaking.backend.store.playStates
import dev.jamesrich.sofaking.backend.store.savePlayStates
import dev.jamesrich.sofaking.backend.store.watchlist
import dev.jamesrich.sofaking.contract.Library
import dev.jamesrich.sofaking.contract.LibraryHome
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.LibraryItemDetail
import dev.jamesrich.sofaking.contract.LibraryPage
import dev.jamesrich.sofaking.contract.LibraryShelf
import dev.jamesrich.sofaking.contract.MediaTrack
import dev.jamesrich.sofaking.contract.UpdatePlayState
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Below this share of a file, a stop counts as not started; at or past [WATCHED_AT], as watched. */
private const val STARTED_AT = 0.05
private const val WATCHED_AT = 0.90

/** Where a frame is taken: a tenth of the way in, past any opening titles, but no later than ten minutes. */
internal fun frameAt(durationMs: Long?): Long =
    durationMs?.let { (it / FRAME_SHARE).coerceAtMost(LATEST_FRAME_MS) } ?: 0

private const val FRAME_SHARE = 10

/** The items under others that search finds, in the order it lists them, after the movies, shows and artists. */
private val SEARCHED_CHILDREN = listOf(ItemKind.EPISODE, ItemKind.ALBUM, ItemKind.TRACK)
private const val LATEST_FRAME_MS = 600_000L

/** How many of the people a search found by it names. */
private const val SEARCH_PEOPLE = 10

/**
 * Where an item's poster or backdrop comes from: a file in its library folder, a TMDB image path, a frame of a video
 * or a picture in a track, the Cover Art Archive, Wikimedia Commons or fanart.tv.
 */
sealed interface ArtSource {
    /** [root] is the library folder; [relative] the image inside it. */
    data class Local(val root: Path, val relative: String) : ArtSource

    /** [path] is TMDB's `/abc.jpg`; [size] one of its sizes, such as `w342`. */
    data class Tmdb(val path: String, val size: String) : ArtSource

    /**
     * A frame of the video [relative] in the library folder [root], taken [atMs] in; [stamp] is the file's size and
     * change time, so a replaced file gets a new frame.
     */
    data class Frame(val root: Path, val relative: String, val atMs: Long, val stamp: String) : ArtSource

    /** The front cover the Cover Art Archive holds for the MusicBrainz release group [releaseGroupId]. */
    data class CoverArt(val releaseGroupId: String) : ArtSource

    /** The Wikimedia Commons image [file], such as `Band live.jpg`. */
    data class Commons(val file: String) : ArtSource

    /** The fanart.tv image at [path] under [FANART_ASSETS]. */
    data class Fanart(val path: String) : ArtSource

    val version: String get() = hash(toString()).take(12)
}

/** A present item as clients read it: its record with the TMDB lookup folded in, and where its artwork comes from. */
internal class View(
    val record: LibraryItemRecord,
    val item: LibraryItem,
    val tagline: String?,
    val studio: String?,
    val cast: List<String>,
    val directors: List<String>,
    val poster: ArtSource?,
    val backdrop: ArtSource?,
    /** For an artist, the ids of the artists in their library ListenBrainz's listeners play alongside them. */
    val similar: List<String> = emptyList(),
    /** For an artist, the ids of their songs ListenBrainz's listeners play most, most played first. */
    val popular: List<String> = emptyList(),
) {
    /** [sortTitle] of the title, built once on first sort instead of on every comparison. */
    val sortKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) { sortTitle(item.title) }
}

/** Every present item of every library, built once per change to the libraries. */
internal class Snapshot(
    val revision: Long,
    val libraries: List<LibraryRecord>,
    val views: Map<String, View>,
    /** A show's seasons, or the episodes of a season or of a show without season folders, in play order. */
    val children: Map<String, List<View>>,
    /** Every episode of a show, in play order. */
    val episodes: Map<String, List<View>>,
    /** The movies, shows and videos of each library. */
    val tops: Map<String, List<View>>,
    /** A movie's or show's extras, in their kinds' order and then by title. */
    val extras: Map<String, List<View>> = emptyMap(),
    /** The albums of each music library. */
    val albums: Map<String, List<View>> = emptyMap(),
    /** The songs of each music library. */
    val songs: Map<String, List<View>> = emptyMap(),
) {
    private val musicLibraries = libraries.filter { it.kind == LibraryKind.MUSIC }.mapTo(HashSet()) { it.id }

    /**
     * The libraries a client sees: Music ones only when it shows [music], as an older app would play a track as a
     * video.
     */
    fun visible(music: Boolean): List<LibraryRecord> =
        if (music) libraries else libraries.filter { it.id !in musicLibraries }

    /** What [library]'s grid lists: its top items, or a Music library's albums or songs with [kind] album or track. */
    fun listed(library: LibraryRecord, kind: String?): List<View> = when {
        kind == null -> tops[library.id].orEmpty()

        kind == ItemKind.ALBUM && library.kind == LibraryKind.MUSIC -> albums[library.id].orEmpty()

        kind == ItemKind.TRACK && library.kind == LibraryKind.MUSIC -> songs[library.id].orEmpty()

        else -> throw ApiFailure(
            HttpStatusCode.BadRequest,
            "INVALID_REQUEST",
            "kind is album or track, in a Music library",
        )
    }

    private val genreLists = ConcurrentHashMap<Pair<String, String?>, List<String>>()

    /** Every genre of what [listed] returns for [library] and [kind], computed once per snapshot. */
    fun genresOf(library: LibraryRecord, kind: String?): List<String> = genreLists.getOrPut(library.id to kind) {
        listed(library, kind).flatMap { it.item.genres }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    /** Whether a client that shows [music] or not sees [view]. */
    fun shows(view: View, music: Boolean): Boolean = music || view.record.libraryId !in musicLibraries

    /** Where [view]'s file is: a movie, episode or video inside its library's folder; null for anything else. */
    fun fileOf(view: View): Path? {
        val relative =
            view.record.path?.takeIf { view.record.kind != ItemKind.SHOW && view.record.kind != ItemKind.SEASON }
                ?: return null
        val library = libraries.firstOrNull { it.id == view.record.libraryId } ?: return null
        val root = Path.of(library.path).normalize()
        return root.resolve(relative).normalize().takeIf { it.startsWith(root) }
    }

    /**
     * Newest added first. Items one scan added together tie, so the newest file breaks the tie (a show's newest
     * episode), then the title.
     */
    val byAdded: Comparator<View> = compareByDescending<View> { it.record.addedAt }
        .thenByDescending { view -> newestFile(view) }
        .thenBy { it.record.sortTitle }

    private val newestFiles = HashMap<String, Instant?>()

    /** Read once per snapshot, as the sort asks for it many times per item. */
    private fun newestFile(view: View): Instant? = synchronized(newestFiles) {
        newestFiles.getOrPut(view.record.id) {
            (episodes[view.record.id] ?: listOf(view)).mapNotNull {
                it.record.modifiedAt?.let { at -> runCatching { Instant.parse(at) }.getOrNull() }
            }.maxOrNull()
        }
    }
}

/**
 * What the app reads from the libraries: the home rows, a library's items, one item's page, search, and the
 * household's watch state. A `.nfo` beside a file wins over TMDB's text, and local artwork over TMDB's images.
 */
class LibraryCatalog internal constructor(
    internal val store: BackendStore,
    internal val clock: Clock,
    /** Changes whenever a scan, a TMDB lookup or an edit changes what the libraries hold. */
    private val revision: () -> Long,
) {
    @Volatile private var snapshot: Snapshot? = null

    /**
     * The watch state, read again only after a library change or one of this catalog's own
     * writes, so browsing reads no table.
     */
    @Volatile private var states: Pair<Pair<Long, Long>, Map<String, PlayStateRecord>>? = null
    internal val stateWrites = AtomicLong()
    private val building = Mutex()
    internal val writing = Mutex()

    internal suspend fun snapshot(): Snapshot {
        val wanted = revision()
        snapshot?.takeIf { it.revision == wanted }?.let { return it }
        return building.withLock {
            snapshot?.takeIf { it.revision == wanted } ?: buildSnapshot(store, wanted).also { snapshot = it }
        }
    }

    internal suspend fun playStates(): Map<String, PlayStateRecord> {
        // Taken before the read, so a write while it runs leaves this copy stale for the next caller to replace.
        val wanted = revision() to stateWrites.get()
        states?.takeIf { it.first == wanted }?.let { return it.second }
        return store.playStates().also { states = wanted to it }
    }

    /** The libraries a client sees, Music ones only when it shows [music]; a Music library counts its artists. */
    suspend fun libraries(music: Boolean = false): List<Library> {
        val snapshot = snapshot()
        return snapshot.visible(music).map {
            Library(it.id, it.name, it.kind.wire, snapshot.tops[it.id].orEmpty().size)
        }
    }

    /** The home rows; a Music library's Recently added row lists albums, and only when the client shows [music]. */
    suspend fun home(music: Boolean = false): LibraryHome {
        val snapshot = snapshot()
        val states = playStates()
        val shelves = ArrayList<LibraryShelf>()
        val continuing = continueWatching(snapshot, states)
        if (continuing.isNotEmpty()) {
            shelves +=
                LibraryShelf(Wire.ShelfId.CONTINUE, "Continue watching", continuing.map { it.out(snapshot, states) })
        }
        val listed = store.watchlist().mapNotNull { it.itemId?.let(snapshot.views::get) }
            .filter { snapshot.shows(it, music = false) }
            .take(SHELF_SIZE)
        if (listed.isNotEmpty()) {
            shelves += LibraryShelf(Wire.ShelfId.WATCHLIST, "Watchlist", listed.map { it.out(snapshot, states) })
        }
        for (library in snapshot.visible(music)) {
            val held = if (library.kind == LibraryKind.MUSIC) snapshot.albums[library.id] else snapshot.tops[library.id]
            val recent = held.orEmpty().sortedWith(snapshot.byAdded).take(SHELF_SIZE)
            if (recent.isNotEmpty()) {
                shelves +=
                    LibraryShelf(
                        Wire.ShelfId.RECENT,
                        "Recently added in ${library.name}",
                        recent.map {
                            it.out(snapshot, states)
                        },
                        library.id,
                    )
            }
        }
        return LibraryHome(shelves, libraries(music))
    }

    /**
     * The movies, shows, videos or artists of [libraryId] (a Music library's albums or songs with [kind] `album` or
     * `track`), sorted by [sort] (`title`, `added`, `year`, `rating`, or `watched` for the most recently watched
     * first; in a Music library `played` for the most recently played first and `plays` for the most played), with
     * [genre] only when given and, with [favorites], only the liked ones, [limit] after [cursor]. The page lists every
     * genre in the library, for the filter, and the first page in title order counts the items under each first
     * letter. A Music library is found only by a client that shows [music].
     */
    suspend fun items(
        libraryId: String,
        sort: String?,
        unwatched: Boolean,
        cursor: String?,
        limit: Int,
        genre: String? = null,
        music: Boolean = false,
        kind: String? = null,
        favorites: Boolean = false,
    ): LibraryPage {
        val snapshot = snapshot()
        val library = snapshot.visible(music).firstOrNull { it.id == libraryId }
            ?: throw ApiFailure(HttpStatusCode.NotFound, "LIBRARY_NOT_FOUND", "No such library")
        val states = playStates()
        val order = itemOrder(sort ?: "title", snapshot, states, libraryId)
        val tops = snapshot.listed(library, kind)
        val all = tops.listed(snapshot, states, genre?.trim()?.ifEmpty { null }, favorites, unwatched)
            .sortedWith(compareBy(order) { it.first })
        val from = cursor?.let {
            it.toIntOrNull()?.takeIf { offset -> offset >= 0 }
                ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid cursor")
        } ?: 0
        val page = all.drop(from).take(limit)
        val next = (from + page.size).takeIf { it < all.size }?.toString()
        val genres = snapshot.genresOf(library, kind)
        val counted = (sort ?: "title") == "title" && cursor == null
        val letters = if (counted) letterRuns(all.map { it.first.item.title }) else null
        val shown = page.map { (view, built) -> built ?: view.out(snapshot, states) }
        return LibraryPage(shown, all.size, next, genres, letters)
    }

    /** An item's page; one in a Music library is found only by a client that shows [music]. */
    suspend fun item(id: String, music: Boolean = false): LibraryItemDetail {
        val snapshot = snapshot()
        val view = snapshot.views[id]?.takeIf { snapshot.shows(it, music) }
            ?: throw ApiFailure(HttpStatusCode.NotFound, "ITEM_NOT_FOUND", "No such item")
        val states = playStates()
        val media = view.record.media
        // Subtitle files beside the video, read now so a file added since the scan shows.
        val sidecars = snapshot.fileOf(view)?.let { withContext(Dispatchers.IO) { sidecarSubtitles(it) } }.orEmpty()
        return LibraryItemDetail(
            item = view.out(snapshot, states),
            children = snapshot.children[id].orEmpty().map { it.out(snapshot, states) },
            next = if (view.record.kind == ItemKind.SHOW) {
                nextEpisode(snapshot.episodes[id].orEmpty(), states)?.out(snapshot, states)
            } else {
                null
            },
            tagline = view.tagline, studio = view.studio, cast = view.cast, directors = view.directors,
            audio = media?.audio.orEmpty().map {
                MediaTrack(it.index, it.codec, it.language, it.title, it.channels, it.default)
            },
            subtitles = (
                media?.subtitles.orEmpty() + sidecars.map {
                    it.stream
                }
                ).map {
                MediaTrack(it.index, it.codec, it.language, it.title, default = it.default, forced = it.forced)
            },
            container = media?.container,
            videoCodec = media?.video?.codec,
            segments = if (view.record.kind == ItemKind.EPISODE) store.segmentsOf(view.record) else emptyList(),
            scrub = if (view.record.path != null) store.scrubOf(view.record) else null,
            extras = snapshot.extras[id].orEmpty().map { it.out(snapshot, states) },
            related = related(snapshot, view).map { it.out(snapshot, states) },
            popular = view.popular.mapNotNull { snapshot.views[it]?.out(snapshot, states) },
        )
    }

    /**
     * Movies, shows, videos and artists whose title holds [query], then the ones credited to people whose names hold
     * it, then episodes, albums and tracks; at most [limit]. Music is searched only for a client that shows [music].
     * The page names those people, the most credited first.
     */
    suspend fun search(query: String, limit: Int, music: Boolean = false): LibraryPage {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "q is required")
        val snapshot = snapshot()
        val states = playStates()
        val matches = snapshot.views.values.filter { needle in it.item.title.lowercase() && snapshot.shows(it, music) }
        val (tops, rest) = matches.partition { it.record.parentId == null }
        val credited = snapshot.views.values.filter { view ->
            view.record.parentId == null && needle !in view.item.title.lowercase() &&
                (view.cast + view.directors).any { needle in it.lowercase() }
        }
        val found = tops.sortedBy { it.sortKey } + credited.sortedBy { it.sortKey } +
            rest.filter { it.record.kind in SEARCHED_CHILDREN }
                .sortedWith(compareBy({ SEARCHED_CHILDREN.indexOf(it.record.kind) }, { it.sortKey }))
        val people = credited.flatMap { view ->
            (view.cast + view.directors).filter { needle in it.lowercase() }.distinct()
        }
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(SEARCH_PEOPLE).map { it.key }
        return LibraryPage(found.take(limit).map { it.out(snapshot, states) }, found.size, people = people)
    }

    /**
     * The movies and shows named by any of [ids], in title order: the answer to "is this one in the library", as a
     * request tool or a webhook asks it.
     */
    suspend fun lookup(ids: ProviderIds): LibraryPage {
        val snapshot = snapshot()
        val states = playStates()
        val found = snapshot.views.values.filter { view ->
            val item = view.item
            (item.kind == ItemKind.MOVIE || item.kind == ItemKind.SHOW) && (
                (ids.tmdb != null && item.tmdbId == ids.tmdb) ||
                    (ids.tvdb != null && item.tvdbId == ids.tvdb) ||
                    (ids.imdb != null && item.imdbId == ids.imdb)
                )
        }.sortedBy { it.sortKey }
        return LibraryPage(found.map { it.out(snapshot, states) }, found.size)
    }

    /**
     * Records where playback of [id] stopped. A stop in the first 5% leaves the item where it was; one past 90% marks
     * it watched. Marking a show or season watched or unwatched marks every episode in it.
     */
    suspend fun updatePlayState(id: String, update: UpdatePlayState): LibraryItem = writing.withLock {
        val snapshot = snapshot()
        val view = snapshot.views[id] ?: throw ApiFailure(HttpStatusCode.NotFound, "ITEM_NOT_FOUND", "No such item")
        if (update.positionMs < 0 || (update.durationMs ?: 0) < 0) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Positions are not negative")
        }
        val now = clock.instant().toString()
        // Counted even when a write fails partway, so the next read is never served the copy from before it.
        try {
            if (view.record.kind == ItemKind.SHOW || view.record.kind == ItemKind.SEASON) {
                markFolder(snapshot, view, update, now)
            } else {
                markItem(view, update, playStates()[id], now)
            }
        } finally {
            stateWrites.incrementAndGet()
        }
        view.out(snapshot, playStates())
    }

    /** Marks every episode of the show or season [view] watched or unwatched. */
    private suspend fun markFolder(snapshot: Snapshot, view: View, update: UpdatePlayState, now: String) {
        val watched = update.watched
            ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "A show or season takes watched only")
        val id = view.record.id
        val episodes =
            if (view.record.kind == ItemKind.SHOW) snapshot.episodes[id].orEmpty() else snapshot.children[id].orEmpty()
        if (watched) {
            store.savePlayStates(episodes.map { PlayStateRecord(it.record.id, 0, it.record.durationMs, true, now) })
        } else {
            store.savePlayStates(emptyList(), episodes.map { it.record.id })
        }
    }

    /**
     * Records where playback of [view] stopped, from its [old] state, which clears a dismissal; or, with
     * [UpdatePlayState.dismissed], only takes it off Continue watching or puts it back.
     */
    private suspend fun markItem(view: View, update: UpdatePlayState, old: PlayStateRecord?, now: String) {
        val id = view.record.id
        val duration = update.durationMs ?: view.record.durationMs ?: old?.durationMs
        update.dismissed?.let { dismissed ->
            store.dismiss(old ?: PlayStateRecord(id, 0, duration, false, now), dismissed)
            return
        }
        val share = if (duration != null && duration > 0) update.positionMs.toDouble() / duration else null
        val (position, watched) = when {
            update.watched != null -> 0L to update.watched!!
            share != null && share >= WATCHED_AT -> 0L to true
            share != null && share < STARTED_AT -> 0L to (old?.watched ?: false)
            else -> update.positionMs to (old?.watched ?: false)
        }
        val state = PlayStateRecord(id, position, duration, watched, now).keeping(old)
        if (state.blank) {
            store.savePlayStates(emptyList(), listOf(id))
        } else {
            store.savePlayStates(listOf(state))
        }
    }
}

/**
 * The views of [this] that pass the filters, each with its built item only when the unwatched filter had to build it,
 * since nothing else reads an item's watch state before the page does.
 */
private fun List<View>.listed(
    snapshot: Snapshot,
    states: Map<String, PlayStateRecord>,
    genre: String?,
    favorites: Boolean,
    unwatched: Boolean,
): List<Pair<View, LibraryItem?>> {
    val matching = filter { view -> genre == null || view.item.genres.any { it.equals(genre, ignoreCase = true) } }
        .filter { view -> !favorites || states[view.record.id]?.favorite == true }
    if (!unwatched) return matching.map { it to null }
    return matching.map { it to it.out(snapshot, states) }.filter { (_, item) ->
        item.unwatched > 0 || (item.kind != Wire.ItemKind.SHOW && item.playState?.watched != true)
    }
}

/**
 * Takes [state]'s item off Continue watching, or with [dismissed] false puts it back; a row with nothing played is
 * deleted rather than kept.
 */
private suspend fun BackendStore.dismiss(state: PlayStateRecord, dismissed: Boolean) {
    if (!dismissed && state.blank) {
        savePlayStates(emptyList(), listOf(state.itemId))
    } else {
        savePlayStates(listOf(state.copy(dismissed = dismissed)))
    }
}

/** The server-relative URL of an item's poster or backdrop; the version changes with the image's source. */
internal fun artworkUrl(itemId: String, role: String, source: ArtSource): String =
    "/artwork/$itemId/$role?v=${source.version}"
