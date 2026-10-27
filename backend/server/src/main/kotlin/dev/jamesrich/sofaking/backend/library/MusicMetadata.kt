package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraryMetadata
import dev.jamesrich.sofaking.backend.store.saveLibraryMetadata
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    /** fanart.tv, for artists' pictures and backdrops and albums' covers; null without a key. */
    private val fanart: FanartClient? = null,
    /** ListenBrainz, for an artist's most played songs and the artists like them; null asks it nothing. */
    private val listenBrainz: ListenBrainzClient? = null,
) {
    /** The libraries whose lookups were asked for again ([forget]) and are not yet marked stale in the store. */
    private val forgotten: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Has [libraryId]'s next pass look up again every album and artist looked up before now, however fresh. The pass
     * first marks the stored lookups stale, keeping what they found, so one a rate limit or a restart cuts short
     * carries on with the rest on the next.
     */
    fun forget(libraryId: String) {
        forgotten.add(libraryId)
    }

    suspend fun refresh(library: LibraryRecord, items: List<LibraryItemRecord>): MetadataResult? {
        if (library.kind != LibraryKind.MUSIC) return null
        val present = items.filter { it.missingSince == null }
        var known = store.libraryMetadata(present.map { it.id })
        if (library.id in forgotten) {
            known = known.mapValues { (_, row) -> row.copy(checkedAt = Instant.EPOCH.toString()) }
            store.saveLibraryMetadata(known.values.toList())
            forgotten.remove(library.id)
        }
        val run = Run(present, known)
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

        /** The rows this run saved, by item id. */
        private val saved = HashMap<String, MetadataRecord>()

        /** Whether fanart.tv is still asked in this run; a refused key stops it. */
        private var fanartOn = fanart != null

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
                        // The cover its artist's fanart.tv lookup found stays until that lookup is made again.
                        fanart = known[album.id]?.facts?.takeIf { facts -> facts.musicBrainzId == it.id }?.fanart,
                    )
                }
            }
        }

        /** Looks [artist] up unless its row is fresh; false once the run stops. */
        suspend fun artist(artist: LibraryItemRecord): Boolean {
            // The id the tags give, else the one most of its matched albums are credited to.
            val hint = artist.details?.musicBrainzId ?: albumsOf[artist.id].orEmpty().mapNotNull { credited[it.id] }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            // A fresh match fanart.tv was never asked about, as before a key was added, is looked up again, as is one
            // looked up before its year, country and members were kept, or before ListenBrainz was asked.
            val row = fresh(artist, hint)
            if (row != null && !incomplete(row.facts, fanartOn, listenBrainz != null)) return true
            return look(artist) {
                val found = hint?.let { client.artist(it) } ?: searchArtist(artist.title)?.let { client.artist(it.id) }
                found?.let {
                    val wikidata = wikidataOf(it)
                    val page = wikidata?.let { id -> client.wikipediaTitle(id) }
                        ?.let { title -> client.wikipediaSummary(title) }
                        ?.takeIf { summary -> summary.type != "disambiguation" }
                    val art = fanartOf(it.id)
                    val listens = listens(listenBrainz, it.id)
                    art?.images?.let { images -> covers(artist, images.covers) }
                    MetadataFacts(
                        overview = page?.extract?.trim()?.ifEmpty { null },
                        genres = genres(it.genres),
                        year = it.lifeSpan?.begin?.take(YEAR_DIGITS)?.toIntOrNull(),
                        studio = it.area?.name?.ifEmpty { null },
                        cast = members(it),
                        described = true,
                        musicBrainzId = it.id,
                        wikidata = wikidata,
                        picture = page?.image?.source?.let(::commonsFile),
                        fanart = art?.images?.picture,
                        fanartBackdrop = art?.images?.backdrop,
                        fanartAsked = art != null,
                        similar = listens?.similar.orEmpty(),
                        popular = listens?.popular.orEmpty(),
                        listened = listens != null,
                    )
                }
            }
        }

        /** What fanart.tv holds for the MusicBrainz artist [id]; null when it was not asked or could not answer. */
        private suspend fun fanartOf(id: String): Fanarted? {
            val client = fanart?.takeIf { fanartOn } ?: return null
            return try {
                Fanarted(client.artist(id))
            } catch (e: FanartFailure) {
                log.warn("fanart.tv lookup failed code={}", e.code)
                if (e.code == "FANART_AUTH") fanartOn = false
                null
            }
        }

        /** Gives each of [artist]'s albums with no cover of its own or the Cover Art Archive's the one in [covers]. */
        private suspend fun covers(artist: LibraryItemRecord, covers: Map<String, String>) {
            val rows = albumsOf[artist.id].orEmpty().filter { it.poster == null && it.id !in pictured }.mapNotNull {
                val row = saved[it.id] ?: known[it.id] ?: return@mapNotNull null
                val facts = row.facts?.takeIf { facts -> !facts.coverArt } ?: return@mapNotNull null
                val cover = covers[facts.musicBrainzId]
                row.copy(facts = facts.copy(fanart = cover)).takeIf { cover != facts.fanart }
            }
            if (rows.isNotEmpty()) store.saveLibraryMetadata(rows)
            rows.forEach { saved[it.itemId] = it }
        }

        /** [item]'s stored row when it is still fresh and was matched to [pinned], the id its tags or albums name. */
        private fun fresh(item: LibraryItemRecord, pinned: String?): MetadataRecord? = known[item.id]?.takeIf { row ->
            val id = row.facts?.musicBrainzId
            // An artist with a Wikidata item but no picture is asked again as soon as a miss, in case one is added.
            val pictureless = row.facts?.wikidata != null && row.facts.picture == null
            val keep = if (id == null || pictureless) MUSIC_UNMATCHED_FOR else MUSIC_MATCHED_FOR
            (pinned == null || id == pinned) && Instant.parse(row.checkedAt).plus(keep) > now
        }

        /** Saves what [lookup] finds for [item], null for nothing; false once a failure ends the run. */
        private suspend fun look(item: LibraryItemRecord, lookup: suspend () -> MetadataFacts?): Boolean = try {
            val facts = lookup()
            fetched++
            unreachable = 0
            if (facts == null) unmatched++ else matched++
            val record = MetadataRecord(item.id, null, now.toString(), facts)
            store.saveLibraryMetadata(listOf(record))
            saved[item.id] = record
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

/** The Wikidata item MusicBrainz links [artist] to. */
private fun wikidataOf(artist: MbArtist): String? = artist.relations.firstOrNull { it.type == "wikidata" }
    ?.url?.resource?.substringAfterLast('/')?.takeIf { WIKIDATA_ID.matches(it) }

/**
 * Whether a fresh artist match's [facts] lack what a lookup now keeps: fanart.tv's images once there is a key
 * ([fanartOn]), ListenBrainz's statistics when it is asked ([listening]), or the year, country and members.
 */
private fun incomplete(facts: MetadataFacts?, fanartOn: Boolean, listening: Boolean): Boolean =
    facts?.musicBrainzId != null &&
        (!facts.described || (fanartOn && !facts.fanartAsked) || (listening && !facts.listened))

/**
 * An image of Wikimedia Commons, as Wikipedia links it: the file itself, or for a large one a thumbnail of it
 * (`thumb.wikimedia.org/…/commons/thumb/a/ab/File.jpg/3840px-File.jpg`), with any query Wikipedia adds
 * (`?utm_source=…`); other pictures may not be free to show.
 */
private val COMMONS_IMAGE = Regex(
    """https://(?:upload|thumb)\.wikimedia\.org/wikipedia/commons/(?:thumb/)?[0-9a-f]/[0-9a-f]{2}/([^/?#]+)""" +
        """(?:/[^/?#]+)?(?:[?#].*)?""",
)

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

private const val YEAR_DIGITS = 4

/** A band's members, as MusicBrainz credits them: those still in it first, each once. */
private fun members(artist: MbArtist): List<String> = artist.relations
    .filter { it.type == "member of band" && it.direction == "backward" && it.artist != null }
    .sortedBy { it.ended }
    .distinctBy { it.artist!!.id }
    .map { it.artist!!.name }
    .filter { it.isNotEmpty() }

private fun genres(genres: List<MbGenre>): List<String> =
    genres.sortedByDescending { it.count }.take(GENRE_LIMIT).map { it.name.replaceFirstChar(Char::uppercase) }

/** What ListenBrainz holds for the MusicBrainz artist [id]; null when it was not asked or could not answer. */
private suspend fun listens(client: ListenBrainzClient?, id: String): Listens? = client?.let {
    runCatchingCancellable { Listens(it.popular(id), it.similar(id)) }.getOrElse { e ->
        log.warn("listenbrainz lookup failed class={} message={}", e.javaClass.simpleName, e.message)
        null
    }
}

/** An artist's [popular] songs and [similar] artists, as ListenBrainz's listeners play them. */
private class Listens(val popular: List<PopularSong>, val similar: List<String>)

/** fanart.tv's answer for an artist: its [images], null when it holds none. */
private class Fanarted(val images: FanartArtist?)
