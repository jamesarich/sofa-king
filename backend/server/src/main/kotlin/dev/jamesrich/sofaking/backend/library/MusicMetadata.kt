package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraryMetadata
import dev.jamesrich.sofaking.backend.store.saveLibraryMetadata
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(MusicMetadata::class.java)

/** How long a MusicBrainz match stays before it is fetched again. */
private val MUSIC_MATCHED_FOR: Duration = Duration.ofDays(30)

/** How long an album or artist MusicBrainz did not find waits before it is searched again. */
private val MUSIC_UNMATCHED_FOR: Duration = Duration.ofDays(7)

/** Lookups in a row that cannot reach MusicBrainz before a run stops. */
private const val MUSIC_UNREACHABLE_STOP = 3

/** The search score out of 100 a MusicBrainz match needs, on top of its names matching. */
private const val MIN_SCORE = 80

/** The genres an album or artist keeps from MusicBrainz, the ones most voted for. */
private const val GENRE_LIMIT = 5

/**
 * Words in brackets at the end of an album title that name an edition, not another album: `(Remastered)`,
 * `[Vinyl]`, `(Deluxe Edition)`, `(2011)`. A search and its match ignore them; the album keeps its own title.
 */
private val EDITION = Regex(
    """(?i)\s*[(\[][^)\]]*(?:remaster|deluxe|edition|expanded|anniversary|bonus|vinyl|\bcd\b|\bweb\b|flac|mp3|""" +
        """explicit|\b\d{4}\b)[^)\]]*[)\]]\s*$""",
)

/**
 * Looks a Music library's albums and artists up on MusicBrainz after each scan. The ids MusicBrainz Picard tags files
 * with pin the match; an album without them is searched by its title and artist, and kept only when MusicBrainz's
 * title and artist are the album's own, editions aside. An artist without an id takes the one its matched albums are
 * credited to, and only failing that is searched by name. A match keeps the MusicBrainz id, the album's first release
 * year, the genres MusicBrainz votes for where the files name none, whether the Cover Art Archive has a cover for an
 * album with no picture of its own, and the artist's Wikidata item, with the first paragraph and the free picture of
 * the English Wikipedia page it links to; nothing renames
 * what the tags call it. Lookups are kept and fetched again after [MUSIC_MATCHED_FOR]; a rate limit that outlasts the
 * client's retries or three lookups in a row that cannot reach MusicBrainz end the run, to resume on the next.
 */
class MusicMetadata internal constructor(
    private val store: BackendStore,
    private val client: MusicBrainzClient,
    private val clock: Clock,
) {
    suspend fun refresh(library: LibraryRecord, items: List<LibraryItemRecord>): MetadataResult? {
        if (library.kind != LibraryKind.MUSIC) return null
        val present = items.filter { it.missingSince == null }
        val run = Run(present, store.libraryMetadata(present.map { it.id }))
        // Each artist right after its albums, whose credits name it, so a run a rate limit ends has done some of both.
        val albums = present.filter { it.kind == ItemKind.ALBUM }.groupBy { it.parentId }
        val artists = present.filter { it.kind == ItemKind.ARTIST }
        val ids = artists.mapTo(HashSet()) { it.id }
        val order = artists.flatMap { artist ->
            albums[artist.id].orEmpty() +
                listOfNotNull(artist.takeIf { musicKey(it.title) != musicKey(VARIOUS_ARTISTS) })
        } + albums.filterKeys { it !in ids }.values.flatten()
        for (item in order) {
            val going = if (item.kind == ItemKind.ALBUM) run.album(item) else run.artist(item)
            if (!going) break
        }
        return run.result()
    }

    /** One refresh of the [present] items, which were looked up as [known]. */
    private inner class Run(present: List<LibraryItemRecord>, private val known: Map<String, MetadataRecord>) {
        private val now = clock.instant()
        private val byId = present.associateBy { it.id }
        private val albumsOf = present.filter { it.kind == ItemKind.ALBUM }.groupBy { it.parentId }

        /** The albums with a track holding a picture. */
        private val pictured = present.filter { it.kind == ItemKind.TRACK && it.media?.picture == true }
            .mapNotNullTo(HashSet()) { it.parentId }

        /** The MusicBrainz artists each album's match is credited to first, by album id. */
        private val credited = HashMap<String, String>()
        private var fetched = 0
        private var matched = 0
        private var unmatched = 0
        private var failed = 0
        private var unreachable = 0
        private var stopped: String? = null

        fun result() = MetadataResult(fetched, matched, unmatched, failed, stopped, source = "MusicBrainz")

        /** Looks [album] up unless its row is fresh and pinned as its tags pin it now; false once the run stops. */
        suspend fun album(album: LibraryItemRecord): Boolean {
            val details = album.details
            val row = fresh(album, details?.releaseGroupId)
            if (row != null) {
                row.facts?.artistId?.let { credited[album.id] = it }
                return true
            }
            return look(album) {
                val group = when {
                    details?.releaseGroupId != null -> client.releaseGroup(details.releaseGroupId)

                    details?.musicBrainzId != null ->
                        client.release(details.musicBrainzId)?.releaseGroup?.id?.let { client.releaseGroup(it) }

                    else -> null
                } ?: search(album)?.let { found -> client.releaseGroup(found.id) ?: found }
                group?.let {
                    val artistId = it.artistCredit.firstOrNull()?.artist?.id?.ifEmpty { null }
                    artistId?.let { id -> credited[album.id] = id }
                    MetadataFacts(
                        year = it.firstReleaseDate?.substringBefore('-')?.toIntOrNull(),
                        genres = if (details?.genres.isNullOrEmpty()) genres(it.genres) else emptyList(),
                        musicBrainzId = it.id,
                        artistId = artistId,
                        coverArt = album.poster == null && album.id !in pictured && client.hasCover(it.id),
                    )
                }
            }
        }

        /** Looks [artist] up unless its row is fresh; false once the run stops. */
        suspend fun artist(artist: LibraryItemRecord): Boolean {
            // The id the tags give, else the one most of its matched albums are credited to.
            val hint = artist.details?.musicBrainzId ?: albumsOf[artist.id].orEmpty().mapNotNull { credited[it.id] }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            if (fresh(artist, hint) != null) return true
            return look(artist) {
                val found = hint?.let { client.artist(it) } ?: searchArtist(artist.title)?.let { client.artist(it.id) }
                found?.let {
                    val wikidata = it.relations.firstOrNull { link -> link.type == "wikidata" }
                        ?.url?.resource?.substringAfterLast('/')?.takeIf { id -> WIKIDATA_ID.matches(id) }
                    val page = wikidata?.let { id -> client.wikipediaTitle(id) }
                        ?.let { title -> client.wikipediaSummary(title) }
                        ?.takeIf { summary -> summary.type != "disambiguation" }
                    MetadataFacts(
                        overview = page?.extract?.trim()?.ifEmpty { null },
                        genres = genres(it.genres),
                        musicBrainzId = it.id,
                        wikidata = wikidata,
                        picture = page?.image?.source?.let(::commonsFile),
                    )
                }
            }
        }

        /** [item]'s stored row when it is still fresh and was matched to [pinned], the id its tags or albums name. */
        private fun fresh(item: LibraryItemRecord, pinned: String?): MetadataRecord? = known[item.id]?.takeIf { row ->
            val id = row.facts?.musicBrainzId
            val keep = if (id == null) MUSIC_UNMATCHED_FOR else MUSIC_MATCHED_FOR
            (pinned == null || id == pinned) && Instant.parse(row.checkedAt).plus(keep) > now
        }

        /** Saves what [lookup] finds for [item], null for nothing; false once a failure ends the run. */
        private suspend fun look(item: LibraryItemRecord, lookup: suspend () -> MetadataFacts?): Boolean = try {
            val facts = lookup()
            fetched++
            unreachable = 0
            if (facts == null) unmatched++ else matched++
            store.saveLibraryMetadata(listOf(MetadataRecord(item.id, null, now.toString(), facts)))
            true
        } catch (e: MusicBrainzFailure) {
            failed++
            log.warn("musicbrainz lookup failed item={} code={}", item.id, e.code)
            when {
                e.code == "MUSICBRAINZ_RATE_LIMITED" -> false.also { stopped = e.code }

                e.code.contains("_UNREACHABLE") && ++unreachable >= MUSIC_UNREACHABLE_STOP ->
                    false.also { stopped = e.code }

                else -> true
            }
        }

        /** The release group called [album]'s title by its artist, editions aside; null when none is clearly it. */
        private suspend fun search(album: LibraryItemRecord): MbReleaseGroup? {
            val artist = album.parentId?.let(byId::get)?.title ?: return null
            val title = album.title.replace(EDITION, "").ifEmpty { album.title }
            return client.searchReleaseGroups(title, artist)
                .filter { it.score >= MIN_SCORE && sameTitle(it.title, title) && creditedTo(it, artist) }
                .maxByOrNull { it.score }
        }

        /** The artist called [name], when one has that name or alias and outscores the rest. */
        private suspend fun searchArtist(name: String): MbArtist? {
            val found = client.searchArtists(name).filter { it.score >= MIN_SCORE }
            val named = found.filter { a ->
                (listOf(a.name) + a.aliases.map { it.name }).any { musicKey(it) == musicKey(name) }
            }
            val best = named.maxByOrNull { it.score } ?: return null
            // Two artists of one name and one score are not told apart by a name alone.
            return best.takeIf { named.count { it.score == best.score } == 1 }
        }
    }
}

/** A Wikidata item's id. */
private val WIKIDATA_ID = Regex("Q[0-9]{1,12}")

/** An image of Wikimedia Commons, as Wikipedia links it; other pictures may not be free to show. */
private val COMMONS_IMAGE = Regex("""https://upload\.wikimedia\.org/wikipedia/commons/[0-9a-f]/[0-9a-f]{2}/([^/?#]+)""")

/** The Commons file name of the image at [url], spaced as Commons names it; null for one Commons does not hold. */
private fun commonsFile(url: String): String? = COMMONS_IMAGE.matchEntire(url)?.groupValues?.get(1)
    ?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8).replace('_', ' ') }

private fun sameTitle(found: String, title: String): Boolean =
    musicKey(found.replace(EDITION, "").ifEmpty { found }) == musicKey(title)

/** Whether [group] is credited to [artist], or to Various Artists for a compilation. */
private fun creditedTo(group: MbReleaseGroup, artist: String): Boolean {
    val credit = group.artistCredit.joinToString(" ") { it.name }
    return musicKey(credit) == musicKey(artist) || group.artistCredit.any { musicKey(it.name) == musicKey(artist) }
}

private fun genres(genres: List<MbGenre>): List<String> =
    genres.sortedByDescending { it.count }.take(GENRE_LIMIT).map { it.name.replaceFirstChar(Char::uppercase) }
