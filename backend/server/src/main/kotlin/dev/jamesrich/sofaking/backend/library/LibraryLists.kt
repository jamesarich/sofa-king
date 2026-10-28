package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraryIntro
import dev.jamesrich.sofaking.backend.store.libraryScrub
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.MediaSegment
import dev.jamesrich.sofaking.contract.PlayState
import dev.jamesrich.sofaking.contract.ScrubThumbnails
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import java.time.Instant

/** How many items a home row holds. */
internal const val SHELF_SIZE = 20

/** How many items More like this lists. */
private const val RELATED_SIZE = 12

/** [view] as clients read it, with the household's watch state from [states]. */
internal fun View.out(snapshot: Snapshot, states: Map<String, PlayStateRecord>): LibraryItem = when (record.kind) {
    ItemKind.SHOW, ItemKind.SEASON -> {
        val episodes = if (record.kind == ItemKind.SHOW) {
            snapshot.episodes[record.id]
        } else {
            snapshot.children[record.id]?.filter { it.record.kind == ItemKind.EPISODE }
        }
        item.copy(unwatched = episodes.orEmpty().count { states[it.record.id]?.watched != true })
    }

    else -> {
        val state = states[record.id]
        item.copy(
            playState = state?.takeIf { it.played || it.dismissed }?.let {
                PlayState(it.positionMs, it.durationMs, it.watched, it.updatedAt, it.dismissed)
            },
            favorite = state?.favorite == true,
            plays = state?.plays ?: 0,
            lastPlayedAt = state?.lastPlayedAt,
        )
    }
}

/**
 * The order [sort] names for the items of [libraryId]: `title`, `added`, `year`, `rating`, `watched` for the most
 * recently watched first, or in a Music library `played` for the most recently played first and `plays` for the most
 * played; an album's or artist's plays are those of its songs.
 */
internal fun itemOrder(
    sort: String,
    snapshot: Snapshot,
    states: Map<String, PlayStateRecord>,
    libraryId: String,
): Comparator<View> {
    val byTitle = compareBy<View>({ it.item.title.let(::sortTitle) }, { it.item.year })
    return when (sort) {
        "title" -> byTitle

        "added" -> snapshot.byAdded

        "year" -> compareByDescending<View, Int?>(nullsFirst()) { it.item.year }.then(byTitle)

        "rating" -> compareByDescending<View, Double?>(nullsFirst()) { it.item.rating }.then(byTitle)

        // A show was watched when any of its episodes was; what was never watched follows by title.
        "watched" -> {
            val lastWatched = snapshot.tops[libraryId].orEmpty().associate { view ->
                view.record.id to lastWatched(snapshot.episodes[view.record.id] ?: listOf(view), states)
            }
            compareBy<View, Instant?>(nullsLast(reverseOrder())) { lastWatched[it.record.id] }.then(byTitle)
        }

        "played" -> {
            val tastes = tastes(snapshot, states, libraryId)
            compareBy<View, Instant?>(nullsLast(reverseOrder())) { tastes[it.record.id]?.lastPlayed }.then(byTitle)
        }

        "plays" -> {
            val tastes = tastes(snapshot, states, libraryId)
            compareByDescending<View> { tastes[it.record.id]?.plays ?: 0 }.then(byTitle)
        }

        else -> throw ApiFailure(
            HttpStatusCode.BadRequest,
            "INVALID_REQUEST",
            "sort is title, added, year, rating, watched, played or plays",
        )
    }
}

/** How often the songs under an item were played, and when last. */
private class Taste(var plays: Int = 0, var lastPlayed: Instant? = null) {
    fun add(other: Taste) {
        plays += other.plays
        if (other.lastPlayed != null && (lastPlayed == null || other.lastPlayed!! > lastPlayed!!)) {
            lastPlayed = other.lastPlayed
        }
    }
}

/** The plays of each song of [libraryId], and the sums for its albums and artists, by item id. */
private fun tastes(snapshot: Snapshot, states: Map<String, PlayStateRecord>, libraryId: String): Map<String, Taste> {
    val byId = HashMap<String, Taste>()
    for (song in snapshot.songs[libraryId].orEmpty()) {
        val state = states[song.record.id]
        val taste = Taste(
            state?.plays ?: 0,
            state?.lastPlayedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
        byId[song.record.id] = taste
        var parent = song.record.parentId
        while (parent != null) {
            byId.getOrPut(parent) { Taste() }.add(taste)
            parent = snapshot.views[parent]?.record?.parentId
        }
    }
    return byId
}

private fun lastWatched(views: List<View>, states: Map<String, PlayStateRecord>): Instant? = views
    .mapNotNull { view -> states[view.record.id]?.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } }
    .maxOrNull()

/**
 * Others of [view]'s library, a movie, show, video or artist, that share its genres: the most shared first, then the
 * best rated; an artist's similar artists first.
 */
internal fun related(snapshot: Snapshot, view: View): List<View> {
    // An artist's similar artists, as ListenBrainz's listeners play them, come before those sharing their genres.
    val similar = view.similar.mapNotNull { snapshot.views[it] }
    return (similar + sharingGenres(snapshot, view)).distinctBy { it.record.id }.take(RELATED_SIZE)
}

private fun sharingGenres(snapshot: Snapshot, view: View): List<View> {
    val genres = view.item.genres.mapTo(HashSet()) { it.lowercase() }
    if (view.record.parentId != null || genres.isEmpty()) return emptyList()
    return snapshot.tops[view.record.libraryId].orEmpty()
        .filter { it.record.id != view.record.id }
        .map { other -> other to other.item.genres.count { it.lowercase() in genres } }
        .filter { it.second > 0 }
        .sortedWith(
            compareByDescending<Pair<View, Int>> { it.second }
                .thenByDescending { it.first.item.rating ?: 0.0 }
                .thenBy { it.first.record.sortTitle },
        )
        .take(RELATED_SIZE)
        .map { it.first }
}

/**
 * The items stopped part way, rewatches included, newest first, with the next episode of each show whose
 * last-played episode was finished; an item the household dismissed stays off until it is played again.
 */
internal fun continueWatching(snapshot: Snapshot, states: Map<String, PlayStateRecord>): List<View> {
    val picks = LinkedHashMap<String, Pair<View, String>>()
    for (state in states.values) {
        if (state.positionMs == 0L || state.dismissed) continue
        val view = snapshot.views[state.itemId]
        // Music has no resume: a track starts over.
        if (view != null && view.record.kind != ItemKind.EXTRA && view.record.kind != ItemKind.TRACK) {
            picks[view.record.id] = view to state.updatedAt
        }
    }
    for (episodes in snapshot.episodes.values) {
        val (next, playedAt) = upNext(episodes, states) ?: continue
        if (next.record.id !in picks && states[next.record.id]?.dismissed != true) {
            picks[next.record.id] = next to playedAt
        }
    }
    // One entry per show: its in-progress or next episode, whichever was touched last.
    return picks.values.sortedByDescending { it.second }.map { it.first }
        .distinctBy { it.item.showId ?: it.record.id }.take(SHELF_SIZE)
}

/** The next episode of a show whose last-played episode was finished, and when that one was played. */
private fun upNext(episodes: List<View>, states: Map<String, PlayStateRecord>): Pair<View, String>? {
    val last = episodes.mapNotNull { states[it.record.id]?.takeIf(PlayStateRecord::played) }
        .maxByOrNull { it.updatedAt }
    if (last == null || !last.watched) return null
    return nextEpisode(episodes, states)?.let { it to last.updatedAt }
}

/**
 * The episode to play next: the one last played if it stopped part way, else the first unwatched one after it, else
 * the first unwatched one. Specials (season 0) are skipped.
 */
internal fun nextEpisode(episodes: List<View>, states: Map<String, PlayStateRecord>): View? {
    val regular = episodes.filter { it.record.season != 0 }
    val last = regular.withIndex().mapNotNull { (index, episode) ->
        states[episode.record.id]?.takeIf(PlayStateRecord::played)?.let { Triple(index, episode, it) }
    }.maxByOrNull { it.third.updatedAt }
    if (last != null && (!last.third.watched || last.third.positionMs > 0)) return last.second
    val unwatched = { episode: View -> states[episode.record.id]?.watched != true }
    val from = last?.first?.plus(1) ?: 0
    return regular.drop(from).firstOrNull(unwatched) ?: regular.firstOrNull(unwatched)
}

/** The scrub thumbnails made from the item's file as it is now; those of a file since replaced are not offered. */
internal suspend fun BackendStore.scrubOf(record: LibraryItemRecord): ScrubThumbnails? {
    val made = libraryScrub(record.id)?.takeIf { it.fileKey == fileKey(record) && it.sheets > 0 } ?: return null
    val version = made.madeAt.hashCode().toUInt().toString(16)
    return ScrubThumbnails(
        made.intervalMs,
        made.tileWidth,
        made.tileHeight,
        made.columns,
        made.rows,
        made.frames,
        List(made.sheets) { "/artwork/${record.id}/scrub/$it?v=$version" },
    )
}

/**
 * The intro and credits found in the episode's file as it is now; those found in a file since replaced are not
 * offered.
 */
internal suspend fun BackendStore.segmentsOf(record: LibraryItemRecord): List<MediaSegment> {
    val found = libraryIntro(record.id)?.takeIf { it.fileKey == fileKey(record) } ?: return emptyList()
    return listOfNotNull(
        found.intro?.let { MediaSegment(Wire.SegmentKind.INTRO, it.startMs, it.endMs) },
        found.credits?.let { MediaSegment(Wire.SegmentKind.CREDITS, it.startMs, it.endMs) },
    )
}
