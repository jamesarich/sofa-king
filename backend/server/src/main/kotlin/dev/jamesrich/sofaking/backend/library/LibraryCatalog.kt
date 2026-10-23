package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraries
import dev.jamesrich.sofaking.backend.store.libraryIntro
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.libraryMetadata
import dev.jamesrich.sofaking.backend.store.playStates
import dev.jamesrich.sofaking.backend.store.savePlayStates
import dev.jamesrich.sofaking.contract.Library
import dev.jamesrich.sofaking.contract.LibraryHome
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.LibraryItemDetail
import dev.jamesrich.sofaking.contract.LibraryPage
import dev.jamesrich.sofaking.contract.LibraryShelf
import dev.jamesrich.sofaking.contract.MediaSegment
import dev.jamesrich.sofaking.contract.MediaTrack
import dev.jamesrich.sofaking.contract.PlayState
import dev.jamesrich.sofaking.contract.UpdatePlayState
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Below this share of a file, a stop counts as not started; at or past [WATCHED_AT], as watched. */
private const val STARTED_AT = 0.05
private const val WATCHED_AT = 0.90

/** How many items a home row holds. */
private const val SHELF_SIZE = 20

/** The kinds that show a frame of their video when they have no picture. */
private val FRAMED = setOf(ItemKind.MOVIE, ItemKind.EPISODE, ItemKind.VIDEO)

/** Where a frame is taken: a tenth of the way in, past any opening titles, but no later than ten minutes. */
internal fun frameAt(durationMs: Long?): Long = durationMs?.let { (it / 10).coerceAtMost(600_000) } ?: 0

/** Where an item's poster or backdrop comes from: a file in its library folder, or a TMDB image path. */
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
)

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
) {
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
    private val store: BackendStore,
    private val clock: Clock,
    /** Changes whenever a scan, a TMDB lookup or an edit changes what the libraries hold. */
    private val revision: () -> Long,
) {
    @Volatile private var snapshot: Snapshot? = null

    /**
     * The watch state, read again only after a library change or one of this catalog's own
     * writes, so browsing reads no table.
     */
    @Volatile private var states: Pair<Pair<Long, Long>, Map<String, PlayStateRecord>>? = null
    private val stateWrites = AtomicLong()
    private val building = Mutex()
    private val writing = Mutex()

    internal suspend fun snapshot(): Snapshot {
        val wanted = revision()
        snapshot?.takeIf { it.revision == wanted }?.let { return it }
        return building.withLock {
            snapshot?.takeIf { it.revision == wanted } ?: build(wanted).also { snapshot = it }
        }
    }

    private suspend fun playStates(): Map<String, PlayStateRecord> {
        // Taken before the read, so a write while it runs leaves this copy stale for the next caller to replace.
        val wanted = revision() to stateWrites.get()
        states?.takeIf { it.first == wanted }?.let { return it.second }
        return store.playStates().also { states = wanted to it }
    }

    suspend fun libraries(): List<Library> {
        val snapshot = snapshot()
        return snapshot.libraries.map { Library(it.id, it.name, it.kind.wire, snapshot.tops[it.id].orEmpty().size) }
    }

    suspend fun home(): LibraryHome {
        val snapshot = snapshot()
        val states = playStates()
        val shelves = ArrayList<LibraryShelf>()
        val continuing = continueWatching(snapshot, states)
        if (continuing.isNotEmpty()) {
            shelves +=
                LibraryShelf(Wire.ShelfId.CONTINUE, "Continue watching", continuing.map { it.out(snapshot, states) })
        }
        for (library in snapshot.libraries) {
            val recent = snapshot.tops[library.id].orEmpty().sortedWith(snapshot.byAdded).take(SHELF_SIZE)
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
        return LibraryHome(shelves, libraries())
    }

    /**
     * The movies, shows or videos of [libraryId], sorted by [sort] (`title`, `added`, `year`, `rating`, or `watched`
     * for the most recently watched first), with [genre] only when given, [limit] after [cursor]. The page lists every
     * genre in the library, for the filter, and the first page in title order counts the items under each first letter.
     */
    suspend fun items(
        libraryId: String,
        sort: String?,
        unwatched: Boolean,
        cursor: String?,
        limit: Int,
        genre: String? = null,
    ): LibraryPage {
        val snapshot = snapshot()
        if (snapshot.libraries.none {
                it.id == libraryId
            }
        ) {
            throw ApiFailure(404, "LIBRARY_NOT_FOUND", "No such library")
        }
        val states = playStates()
        val byTitle = compareBy<View>({ it.item.title.let(::sortTitle) }, { it.item.year })
        val order = when (sort ?: "title") {
            "title" -> byTitle

            "added" -> snapshot.byAdded

            "year" -> compareByDescending<View, Int?>(nullsFirst()) { it.item.year }.then(byTitle)

            "rating" -> compareByDescending<View, Double?>(nullsFirst()) { it.item.rating }.then(byTitle)

            // A show was watched when any of its episodes was; what was never watched follows by title.
            "watched" -> {
                val lastWatched = snapshot.tops[libraryId].orEmpty().associate { view ->
                    view.record.id to
                        (snapshot.episodes[view.record.id]?.map { it.record.id } ?: listOf(view.record.id))
                            .mapNotNull { id ->
                                states[id]?.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                            }
                            .maxOrNull()
                }
                compareBy<View, Instant?>(nullsLast(reverseOrder())) { lastWatched[it.record.id] }.then(byTitle)
            }

            else -> throw ApiFailure(400, "INVALID_REQUEST", "sort is title, added, year, rating or watched")
        }
        val tops = snapshot.tops[libraryId].orEmpty()
        val wanted = genre?.trim()?.ifEmpty { null }
        val all = tops
            .filter { view -> wanted == null || view.item.genres.any { it.equals(wanted, ignoreCase = true) } }
            .map { it to it.out(snapshot, states) }
            .filter { (_, item) ->
                !unwatched || item.unwatched > 0 || (item.kind != Wire.ItemKind.SHOW && item.playState?.watched != true)
            }
            .sortedWith(compareBy(order) { it.first })
        val from =
            cursor?.let {
                it.toIntOrNull()?.takeIf { offset -> offset >= 0 }
                    ?: throw ApiFailure(400, "INVALID_REQUEST", "Invalid cursor")
            }
                ?: 0
        val page = all.drop(from).take(limit)
        val next = (from + page.size).takeIf { it < all.size }?.toString()
        val genres = tops.flatMap { it.item.genres }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        val letters = if ((sort ?: "title") == "title" && cursor == null) {
            letterRuns(all.map { it.first.item.title })
        } else {
            null
        }
        return LibraryPage(page.map { it.second }, all.size, next, genres, letters)
    }

    suspend fun item(id: String): LibraryItemDetail {
        val snapshot = snapshot()
        val view = snapshot.views[id] ?: throw ApiFailure(404, "ITEM_NOT_FOUND", "No such item")
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
            segments = if (view.record.kind == ItemKind.EPISODE) segments(view.record) else emptyList(),
        )
    }

    /**
     * The intro and credits found in the episode's file as it is now; those found in a file
     * since replaced are not offered.
     */
    private suspend fun segments(record: LibraryItemRecord): List<MediaSegment> {
        val found = store.libraryIntro(record.id)?.takeIf { it.fileKey == fileKey(record) } ?: return emptyList()
        return listOfNotNull(
            found.intro?.let { MediaSegment(Wire.SegmentKind.INTRO, it.startMs, it.endMs) },
            found.credits?.let { MediaSegment(Wire.SegmentKind.CREDITS, it.startMs, it.endMs) },
        )
    }

    /** Movies, shows and videos whose title holds [query], then episodes; at most [limit]. */
    suspend fun search(query: String, limit: Int): LibraryPage {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) throw ApiFailure(400, "INVALID_REQUEST", "q is required")
        val snapshot = snapshot()
        val states = playStates()
        val matches = snapshot.views.values.filter { needle in it.item.title.lowercase() }
        val (tops, rest) = matches.partition { it.record.parentId == null }
        val credited = snapshot.views.values.filter { view ->
            view.record.parentId == null && needle !in view.item.title.lowercase() &&
                (view.cast + view.directors).any { needle in it.lowercase() }
        }
        val found = tops.sortedBy { sortTitle(it.item.title) } + credited.sortedBy { sortTitle(it.item.title) } +
            rest.filter { it.record.kind == ItemKind.EPISODE }.sortedBy { sortTitle(it.item.title) }
        return LibraryPage(found.take(limit).map { it.out(snapshot, states) }, found.size)
    }

    /**
     * Records where playback of [id] stopped. A stop in the first 5% leaves the item where it was; one past 90% marks
     * it watched. Marking a show or season watched or unwatched marks every episode in it.
     */
    suspend fun updatePlayState(id: String, update: UpdatePlayState): LibraryItem = writing.withLock {
        val snapshot = snapshot()
        val view = snapshot.views[id] ?: throw ApiFailure(404, "ITEM_NOT_FOUND", "No such item")
        val states = playStates()
        val now = clock.instant().toString()
        val folder = view.record.kind == ItemKind.SHOW || view.record.kind == ItemKind.SEASON
        if (update.positionMs < 0 || (update.durationMs ?: 0) < 0) {
            throw ApiFailure(400, "INVALID_REQUEST", "Positions are not negative")
        }
        // Counted even when a write fails partway, so the next read is never served the copy from before it.
        try {
            if (folder) {
                val watched =
                    update.watched ?: throw ApiFailure(400, "INVALID_REQUEST", "A show or season takes watched only")
                val episodes = if (view.record.kind == ItemKind.SHOW) {
                    snapshot.episodes[id].orEmpty()
                } else {
                    snapshot.children[id].orEmpty()
                }
                if (watched) {
                    store.savePlayStates(
                        episodes.map {
                            PlayStateRecord(it.record.id, 0, it.record.durationMs, true, now)
                        },
                    )
                } else {
                    store.savePlayStates(emptyList(), episodes.map { it.record.id })
                }
            } else {
                val old = states[id]
                val duration = update.durationMs ?: view.record.durationMs ?: old?.durationMs
                val share = if (duration != null && duration > 0) update.positionMs.toDouble() / duration else null
                val (position, watched) = when {
                    update.watched != null -> 0L to update.watched!!
                    share != null && share >= WATCHED_AT -> 0L to true
                    share != null && share < STARTED_AT -> 0L to (old?.watched ?: false)
                    else -> update.positionMs to (old?.watched ?: false)
                }
                if (position == 0L && !watched) {
                    store.savePlayStates(emptyList(), listOf(id))
                } else {
                    store.savePlayStates(listOf(PlayStateRecord(id, position, duration, watched, now)))
                }
            }
        } finally {
            stateWrites.incrementAndGet()
        }
        view.out(snapshot, playStates())
    }

    private suspend fun build(revision: Long): Snapshot {
        val libraries = store.libraries()
        val records = libraries.flatMap { library ->
            store.libraryItems(library.id).filter { it.missingSince == null }.map {
                library to
                    it
            }
        }
        val facts = store.libraryMetadata(records.map { it.second.id })
        val byId = records.associate { (_, record) -> record.id to record }
        val shows = HashMap<String, LibraryItemRecord>()
        for ((_, record) in records) {
            var at: LibraryItemRecord? = record
            while (at != null && at.kind != ItemKind.SHOW) at = at.parentId?.let(byId::get)
            if (at != null) shows[record.id] = at
        }
        val views = LinkedHashMap<String, View>()
        // Shows first, so a season or episode can borrow its show's artwork.
        for ((library, record) in records.sortedBy { if (it.second.kind == ItemKind.SHOW) 0 else 1 }) {
            val show = shows[record.id]?.takeIf { it.id != record.id }
            views[record.id] = view(library, record, facts[record.id]?.facts, show, show?.let { views[it.id] })
        }
        val playOrder =
            compareBy<View>({ it.record.season ?: Int.MAX_VALUE }, { it.record.episode ?: Int.MAX_VALUE }, {
                it.record.aired
                    ?: ""
            }, { it.record.sortTitle })
        val children = views.values.filter {
            it.record.parentId != null
        }.groupBy { it.record.parentId!! }.mapValues { (_, list) -> list.sortedWith(playOrder) }
        val episodes = views.values.filter {
            it.record.kind == ItemKind.EPISODE
        }.groupBy { shows[it.record.id]!!.id }.mapValues { (_, list) -> list.sortedWith(playOrder) }
        val tops = views.values.filter { it.record.parentId == null }.groupBy { it.record.libraryId }
        // A show's count is its seasons, or its episodes when it has no season folders.
        val counted = views.mapValues { (id, view) ->
            View(
                view.record,
                view.item.copy(children = children[id].orEmpty().size),
                view.tagline,
                view.studio,
                view.cast,
                view.directors,
                view.poster,
                view.backdrop,
            )
        }
        fun List<View>.fresh() = map { counted.getValue(it.record.id) }
        return Snapshot(
            revision,
            libraries,
            counted,
            children.mapValues {
                it.value.fresh()
            },
            episodes.mapValues { it.value.fresh() },
            tops.mapValues { it.value.fresh() },
        )
    }

    private fun view(
        library: LibraryRecord,
        record: LibraryItemRecord,
        facts: MetadataFacts?,
        show: LibraryItemRecord?,
        showView: View?,
    ): View {
        val details = record.details
        val nfo = details?.nfo == true
        val root = Path.of(library.path)
        fun <T> nfoFirst(own: T?, tmdb: T?): T? = if (nfo && own != null) own else tmdb ?: own
        fun nfoFirst(own: List<String>?, tmdb: List<String>?): List<String> = if (nfo && !own.isNullOrEmpty()) {
            own
        } else {
            tmdb.takeUnless { it.isNullOrEmpty() } ?: own.orEmpty()
        }
        val title = if (nfo) record.title else facts?.title ?: record.title
        // A file with no picture of its own shows a frame from it, as an episode's or a
        // video's still and a movie's backdrop.
        val frame = record.path?.takeIf { record.media?.video != null && record.kind in FRAMED }?.let {
            ArtSource.Frame(
                root,
                it,
                frameAt(record.durationMs ?: record.media?.durationMs),
                "${record.size}-${record.modifiedAt}",
            )
        }
        val poster = record.poster?.let { ArtSource.Local(root, it) }
            ?: facts?.poster?.let { ArtSource.Tmdb(it, if (record.kind == ItemKind.EPISODE) "w300" else "w342") }
            ?: showView?.poster.takeIf { record.kind == ItemKind.SEASON }
            ?: frame?.takeIf { record.kind != ItemKind.MOVIE }
        val backdrop =
            record.backdrop?.let { ArtSource.Local(root, it) } ?: facts?.backdrop?.let { ArtSource.Tmdb(it, "w1280") }
                ?: showView?.backdrop
                ?: frame
        val video = record.media?.video
        val item = LibraryItem(
            id = record.id, libraryId = record.libraryId, kind = record.kind, title = title, parentId = record.parentId,
            showId = show?.id, showTitle = showView?.item?.title ?: show?.title,
            year = record.year ?: facts?.year, season = record.season, episode = record.episode,
            episodeEnd = record.episodeEnd,
            aired = record.aired ?: facts?.aired, overview = record.overview ?: facts?.overview,
            durationMs = record.durationMs ?: facts?.runtimeMinutes?.let { it * 60_000L },
            poster = poster?.let {
                artworkUrl(
                    if (it == showView?.poster && record.kind == ItemKind.SEASON) {
                        show!!.id
                    } else {
                        record.id
                    },
                    "poster",
                    it,
                )
            },
            backdrop = backdrop?.let {
                artworkUrl(
                    if (it == showView?.backdrop) {
                        show!!.id
                    } else {
                        record.id
                    },
                    "backdrop",
                    it,
                )
            },
            addedAt = record.addedAt,
            genres = nfoFirst(
                details?.genres,
                facts?.genres,
            ),
            contentRating = nfoFirst(details?.contentRating, facts?.contentRating),
            rating = nfoFirst(details?.rating, facts?.rating), videoHeight = video?.height,
            hdr =
            video?.transfer != null,
        )
        return View(
            record,
            item,
            nfoFirst(details?.tagline, facts?.tagline),
            nfoFirst(details?.studio, facts?.studio),
            nfoFirst(details?.cast, facts?.cast),
            nfoFirst(details?.directors, facts?.directors),
            poster,
            backdrop,
        )
    }

    /**
     * The items stopped part way, rewatches included, newest first, with the next episode of each show whose
     * last-played episode was finished.
     */
    private fun continueWatching(snapshot: Snapshot, states: Map<String, PlayStateRecord>): List<View> {
        val picks = LinkedHashMap<String, Pair<View, String>>()
        for (state in states.values) {
            val view = snapshot.views[state.itemId] ?: continue
            if (state.positionMs > 0) picks[view.record.id] = view to state.updatedAt
        }
        for ((_, episodes) in snapshot.episodes) {
            val last =
                episodes.mapNotNull { episode ->
                    states[episode.record.id]?.let { episode to it }
                }.maxByOrNull { it.second.updatedAt }
                    ?: continue
            if (!last.second.watched) continue
            val next = nextEpisode(episodes, states) ?: continue
            if (next.record.id !in picks) picks[next.record.id] = next to last.second.updatedAt
        }
        // One entry per show: its in-progress or next episode, whichever was touched last.
        return picks.values.sortedByDescending { it.second }.map { it.first }
            .distinctBy { it.item.showId ?: it.record.id }.take(SHELF_SIZE)
    }

    /**
     * The episode to play next: the one last played if it stopped part way, else the first unwatched one after it, else
     * the first unwatched one. Specials (season 0) are skipped.
     */
    private fun nextEpisode(episodes: List<View>, states: Map<String, PlayStateRecord>): View? {
        val regular = episodes.filter { it.record.season != 0 }
        val last = regular.withIndex().mapNotNull { (index, episode) ->
            states[episode.record.id]?.let { Triple(index, episode, it) }
        }.maxByOrNull { it.third.updatedAt }
        if (last != null && (!last.third.watched || last.third.positionMs > 0)) return last.second
        val unwatched = { episode: View -> states[episode.record.id]?.watched != true }
        val from = last?.first?.plus(1) ?: 0
        return regular.drop(from).firstOrNull(unwatched) ?: regular.firstOrNull(unwatched)
    }

    private fun View.out(snapshot: Snapshot, states: Map<String, PlayStateRecord>): LibraryItem = when (record.kind) {
        ItemKind.SHOW, ItemKind.SEASON -> {
            val episodes = if (record.kind == ItemKind.SHOW) {
                snapshot.episodes[record.id]
            } else {
                snapshot.children[record.id]?.filter { it.record.kind == ItemKind.EPISODE }
            }
            item.copy(unwatched = episodes.orEmpty().count { states[it.record.id]?.watched != true })
        }

        else -> item.copy(
            playState = states[record.id]?.let {
                PlayState(it.positionMs, it.durationMs, it.watched, it.updatedAt)
            },
        )
    }
}

/** The server-relative URL of an item's poster or backdrop; the version changes with the image's source. */
internal fun artworkUrl(itemId: String, role: String, source: ArtSource): String =
    "/artwork/$itemId/$role?v=${source.version}"
