package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.contract.LibraryPage
import io.ktor.http.HttpStatusCode
import kotlin.math.pow
import kotlin.random.Random

/** How many songs a radio queues unless the client asks for fewer or more. */
internal const val RADIO_SIZE = 50

/** The most songs of one artist a radio plays in a row. */
private const val MAX_RUN = 2

/** How much more likely a song is to be drawn when it is the seed artist's own, a similar artist's or a genre's. */
private const val OWN_WEIGHT = 4.0
private const val SIMILAR_WEIGHT = 2.0
private const val GENRE_WEIGHT = 1.0

/** How much more likely a song ListenBrainz's listeners play most is to be drawn than the rest of its artist's. */
private const val POPULAR_BOOST = 3.0

/**
 * The songs of a radio from [seed], an artist, an album or a song, in the order to play: the seed's artist's songs
 * most, then those of artists listeners play alongside them, then of artists sharing their genres, the songs listeners
 * play most likelier than the rest. A song seed plays first and an album seed opens with its first song. With no seed
 * the radio draws from every artist of [libraryId] alike. No more than [MAX_RUN] songs of one artist play in a row
 * while another artist has any left. At most [limit] songs, each once.
 */
internal fun radioSongs(snapshot: Snapshot, seed: View?, libraryId: String, limit: Int, random: Random): List<View> {
    val artist = seed?.let { artistOf(snapshot, it) }
    val pools = if (artist == null) {
        snapshot.tops[libraryId].orEmpty().map { it to GENRE_WEIGHT }
    } else {
        val similar = artist.similar.toSet()
        listOf(artist to OWN_WEIGHT) + related(snapshot, artist).map {
            it to if (it.record.id in similar) SIMILAR_WEIGHT else GENRE_WEIGHT
        }
    }
    val drawn = pools.flatMap { (pool, weight) ->
        val popular = pool.popular.toSet()
        songsOf(snapshot, pool).map { song ->
            val share = weight * if (song.record.id in popular) POPULAR_BOOST else 1.0
            // Drawing without replacement by weight: a key of random^(1/weight), the biggest first.
            song to random.nextDouble().pow(1 / share)
        }
    }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.record.id }
    val first = seedSong(snapshot, seed)
    return spread(listOfNotNull(first) + drawn.filter { it.record.id != first?.record?.id }, limit)
}

/** The artist [view] belongs to, or [view] itself. */
private fun artistOf(snapshot: Snapshot, view: View): View? = when (view.record.kind) {
    ItemKind.ARTIST -> view
    ItemKind.ALBUM -> view.record.parentId?.let(snapshot.views::get)
    ItemKind.TRACK -> view.record.parentId?.let(snapshot.views::get)?.record?.parentId?.let(snapshot.views::get)
    else -> null
}

/** Every song of [artist], album by album. */
private fun songsOf(snapshot: Snapshot, artist: View): List<View> = snapshot.children[artist.record.id].orEmpty()
    .flatMap { album -> snapshot.children[album.record.id].orEmpty() }
    .filter { it.record.kind == ItemKind.TRACK }

/** The song a radio opens with: a song seed itself, an album seed's first song. */
private fun seedSong(snapshot: Snapshot, seed: View?): View? = when (seed?.record?.kind) {
    ItemKind.TRACK -> seed
    ItemKind.ALBUM -> snapshot.children[seed.record.id].orEmpty().firstOrNull { it.record.kind == ItemKind.TRACK }
    else -> null
}

/** [songs] in order, taking the next of another artist whenever the last [MAX_RUN] taken share one. */
private fun spread(songs: List<View>, limit: Int): List<View> {
    val waiting = ArrayDeque(songs)
    val out = ArrayList<View>()
    while (out.size < limit && waiting.isNotEmpty()) {
        val run = out.takeLast(MAX_RUN).map { it.item.artistId }.distinct()
        val atRun = out.size >= MAX_RUN && run.size == 1
        val at = if (atRun) waiting.indexOfFirst { it.item.artistId != run.single() }.takeIf { it >= 0 } ?: 0 else 0
        out += waiting.removeAt(at)
    }
    return out
}

/**
 * A radio of at most [limit] songs from the artist, album or song [id], as a client that shows [music] asks it; see
 * [radioSongs].
 */
internal suspend fun LibraryCatalog.radio(
    id: String,
    limit: Int,
    music: Boolean = false,
    random: Random = Random.Default,
): LibraryPage {
    val snapshot = snapshot()
    val seed = snapshot.views[id]?.takeIf { snapshot.shows(it, music) }
        ?: throw ApiFailure(HttpStatusCode.NotFound, "ITEM_NOT_FOUND", "No such item")
    if (seed.record.kind !in setOf(ItemKind.ARTIST, ItemKind.ALBUM, ItemKind.TRACK)) {
        throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "a radio starts from an artist, album or song")
    }
    return radioPage(snapshot, seed, seed.record.libraryId, limit, random)
}

/** A radio of at most [limit] songs from every artist of the Music library [libraryId], alike. */
internal suspend fun LibraryCatalog.libraryRadio(
    libraryId: String,
    limit: Int,
    music: Boolean = false,
    random: Random = Random.Default,
): LibraryPage {
    val snapshot = snapshot()
    val library = snapshot.visible(music).firstOrNull { it.id == libraryId && it.kind == LibraryKind.MUSIC }
        ?: throw ApiFailure(HttpStatusCode.NotFound, "LIBRARY_NOT_FOUND", "No such Music library")
    return radioPage(snapshot, null, library.id, limit, random)
}

private suspend fun LibraryCatalog.radioPage(
    snapshot: Snapshot,
    seed: View?,
    libraryId: String,
    limit: Int,
    random: Random,
): LibraryPage {
    val states = playStates()
    val songs = radioSongs(snapshot, seed, libraryId, limit, random).map { it.out(snapshot, states) }
    return LibraryPage(songs, songs.size)
}
