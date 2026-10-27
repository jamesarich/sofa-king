package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraries
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.libraryMetadata
import dev.jamesrich.sofaking.contract.LibraryItem
import java.nio.file.Path

/** The kinds that show a frame of their video when they have no picture. */
private val FRAMED = setOf(ItemKind.MOVIE, ItemKind.EPISODE, ItemKind.VIDEO, ItemKind.EXTRA)

/** Seasons, then episodes, then air date, then title. */
private val PLAY_ORDER = compareBy<View>(
    { it.record.season ?: Int.MAX_VALUE },
    { it.record.episode ?: Int.MAX_VALUE },
    { it.record.aired ?: "" },
    { it.record.sortTitle },
)

/** An artist's albums, newest first, then by title. */
private val ALBUM_ORDER = compareByDescending<View> { it.record.year ?: 0 }.thenBy { it.record.sortTitle }

/** Every present item of every library as the store holds them now. */
internal suspend fun buildSnapshot(store: BackendStore, revision: Long): Snapshot {
    val libraries = store.libraries()
    val records = libraries.flatMap { library ->
        store.libraryItems(library.id).filter { it.missingSince == null }.map { library to it }
    }
    val facts = store.libraryMetadata(records.map { it.second.id })
    val shows = showsOf(records.map { it.second })
    val music = MusicParents(records.map { it.second }, facts)
    val views = LinkedHashMap<String, View>()
    // Shows and albums first, so a season, an episode, a track or an artist can borrow their artwork.
    for ((library, record) in records.sortedBy { if (it.second.kind in BORROWED) 0 else 1 }) {
        val show = shows[record.id]?.takeIf { it.id != record.id }
        val view = itemView(library, record, facts[record.id], show, show?.let { views[it.id] })
        views[record.id] =
            if (library.kind == LibraryKind.MUSIC) music.view(view, library, views, facts[record.id]?.facts) else view
    }
    return arranged(revision, libraries, views, shows)
}

/** The kinds whose artwork others borrow. */
private val BORROWED = setOf(ItemKind.SHOW, ItemKind.ALBUM)

/** Who a music library's albums and tracks belong to, and the pictures they share. */
private class MusicParents(records: List<LibraryItemRecord>, facts: Map<String, MetadataRecord>) {
    private val byId = records.associateBy { it.id }
    private val albums = records.filter { it.kind == ItemKind.ALBUM }.groupBy { it.parentId }

    /** Each artist matched on MusicBrainz, by the MusicBrainz id. */
    private val artists = records.filter { it.kind == ItemKind.ARTIST }
        .mapNotNull { artist -> facts[artist.id]?.facts?.musicBrainzId?.let { it to artist.id } }
        .toMap()

    /** Each artist's songs, by the artist's id. */
    private val songs = records.filter { it.kind == ItemKind.TRACK }
        .groupBy { track -> track.parentId?.let(byId::get)?.parentId }

    /** [artist]'s songs ListenBrainz's listeners play most, by their recording id or else their title. */
    private fun popular(artist: LibraryItemRecord, facts: MetadataFacts?): List<String> {
        val popular = facts?.popular?.takeIf { artist.kind == ItemKind.ARTIST }.orEmpty()
        if (popular.isEmpty()) return emptyList()
        val own = songs[artist.id].orEmpty().sortedBy { it.sortTitle }
        val byRecording = own.filter { it.details?.musicBrainzId != null }.associateBy { it.details?.musicBrainzId }
        val byTitle = own.groupBy { musicKey(it.title) }
        return popular.mapNotNull { song ->
            (song.id?.let(byRecording::get) ?: byTitle[musicKey(song.title)]?.firstOrNull())?.id
        }.distinct().take(POPULAR_SHOWN)
    }

    /** The first track of each album holding a picture, by its album's id. */
    private val pictured = records.filter { it.kind == ItemKind.TRACK && it.media?.picture == true }
        .sortedBy { it.sortTitle }
        .groupBy { it.parentId }

    /** For an album without a cover file, the picture in its first track that holds one. */
    private fun embedded(record: LibraryItemRecord, library: LibraryRecord): ArtSource? =
        pictured[record.id]?.firstOrNull()?.takeIf { record.kind == ItemKind.ALBUM }?.let { track ->
            ArtSource.Frame(Path.of(library.path), track.path!!, 0, "${track.size}-${track.modifiedAt}")
        }

    /** An artist's picture from its lookup: fanart.tv's, else Wikipedia's. */
    private fun looked(record: LibraryItemRecord, facts: MetadataFacts?): ArtSource? =
        facts?.takeIf { record.kind == ItemKind.ARTIST }?.let {
            it.fanart?.let(ArtSource::Fanart) ?: it.picture?.let(ArtSource::Commons)
        }

    /** An album's cover from its lookup: the Cover Art Archive's, else fanart.tv's. */
    private fun cover(record: LibraryItemRecord, facts: MetadataFacts?): ArtSource? =
        facts?.takeIf { record.kind == ItemKind.ALBUM }?.let {
            it.musicBrainzId?.takeIf { _ -> it.coverArt }?.let(ArtSource::CoverArt) ?: it.fanart?.let(ArtSource::Fanart)
        }

    /**
     * [view] of [library] with its album and artist, and their pictures: an album without a cover shows the picture in
     * its first track that has one, else the Cover Art Archive's cover its [facts] found, else fanart.tv's, a track its
     * album's, an artist without its own fanart.tv's picture, else the Wikipedia one its [facts] found, else its newest
     * album's, and fanart.tv's backdrop when it has none of its own.
     */
    fun view(view: View, library: LibraryRecord, views: Map<String, View>, facts: MetadataFacts?): View {
        val record = view.record
        val album = when (record.kind) {
            ItemKind.TRACK -> record.parentId?.let(byId::get)
            ItemKind.ALBUM -> record
            else -> null
        }
        val artist = album?.parentId?.let(byId::get)
        val own = view.poster ?: looked(record, facts)
        // A borrowed picture is served from the item it belongs to, so every item showing it shares one image.
        val borrowedFrom = when {
            own != null -> null

            record.kind == ItemKind.TRACK -> album

            record.kind == ItemKind.ARTIST -> albums[record.id].orEmpty().sortedByDescending { it.year ?: 0 }
                .firstOrNull { views[it.id]?.poster != null }

            else -> null
        }
        val poster = own ?: borrowedFrom?.let { views[it.id]?.poster } ?: embedded(record, library)
            ?: cover(record, facts)
        val backdrop = view.backdrop
            ?: facts?.fanartBackdrop?.takeIf { record.kind == ItemKind.ARTIST }?.let(ArtSource::Fanart)
        val owner = borrowedFrom?.takeIf { views[it.id]?.poster != null } ?: record
        return View(
            record,
            view.item.copy(
                poster = poster?.let { artworkUrl(owner.id, "poster", it) },
                backdrop = backdrop?.let { artworkUrl(record.id, "backdrop", it) } ?: view.item.backdrop,
                artistId = artist?.id,
                artist = artist?.title,
                trackArtist = record.details?.artist.takeIf { record.kind == ItemKind.TRACK },
                album = album?.title.takeIf { record.kind == ItemKind.TRACK },
                track = record.details?.track,
                disc = record.details?.disc,
            ),
            view.tagline,
            view.studio,
            view.cast,
            view.directors,
            poster,
            backdrop,
            similar = facts?.similar.orEmpty().takeIf { record.kind == ItemKind.ARTIST }.orEmpty()
                .mapNotNull(artists::get).filter { it != record.id }.distinct(),
            popular = popular(record, facts),
        )
    }
}

/** How many of an artist's most played songs their page lists. */
private const val POPULAR_SHOWN = 10

/** The show each of [records] belongs to, by its id; a show belongs to itself. */
private fun showsOf(records: List<LibraryItemRecord>): Map<String, LibraryItemRecord> {
    val byId = records.associateBy { it.id }
    val shows = HashMap<String, LibraryItemRecord>()
    for (record in records) {
        var at: LibraryItemRecord? = record
        while (at != null && at.kind != ItemKind.SHOW) at = at.parentId?.let(byId::get)
        if (at != null) shows[record.id] = at
    }
    return shows
}

/** [views] grouped under their parents, shows and libraries, each item counting its children. */
private fun arranged(
    revision: Long,
    libraries: List<LibraryRecord>,
    views: Map<String, View>,
    shows: Map<String, LibraryItemRecord>,
): Snapshot {
    val (extraViews, held) =
        views.values.filter { it.record.parentId != null }.partition { it.record.kind == ItemKind.EXTRA }
    val children = held.groupBy { it.record.parentId!! }.mapValues { (_, list) ->
        list.sortedWith(if (list.first().record.kind == ItemKind.ALBUM) ALBUM_ORDER else PLAY_ORDER)
    }
    val extras =
        extraViews.groupBy { it.record.parentId!! }.mapValues { (_, list) -> list.sortedBy { it.record.sortTitle } }
    val episodes = views.values.filter { it.record.kind == ItemKind.EPISODE }
        .groupBy { shows[it.record.id]!!.id }
        .mapValues { (_, list) -> list.sortedWith(PLAY_ORDER) }
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
            view.similar,
            view.popular,
        )
    }
    fun List<View>.fresh() = map { counted.getValue(it.record.id) }
    return Snapshot(
        revision,
        libraries,
        counted,
        children.mapValues { it.value.fresh() },
        episodes.mapValues { it.value.fresh() },
        tops.mapValues { it.value.fresh() },
        extras.mapValues { it.value.fresh() },
        views.values.filter { it.record.kind == ItemKind.ALBUM }.groupBy { it.record.libraryId }
            .mapValues { it.value.fresh() },
    )
}

/**
 * [record] of [library] as clients read it, with what TMDB found ([lookup]) and, for an episode or season, its show.
 */
private fun itemView(
    library: LibraryRecord,
    record: LibraryItemRecord,
    lookup: MetadataRecord?,
    show: LibraryItemRecord?,
    showView: View?,
): View {
    val facts = lookup?.facts
    val text = ItemText(record, facts)
    val ids = providerIds(record, lookup)
    val art = ItemArt(Path.of(library.path), record, facts, showView)
    val video = record.media?.video
    val item = LibraryItem(
        id = record.id, libraryId = record.libraryId, kind = record.kind, title = text.title,
        parentId = record.parentId,
        showId = show?.id, showTitle = showView?.item?.title ?: show?.title,
        year = record.year ?: facts?.year, season = record.season, episode = record.episode,
        episodeEnd = record.episodeEnd,
        aired = record.aired ?: facts?.aired, overview = record.overview ?: facts?.overview,
        durationMs = record.durationMs ?: facts?.runtimeMinutes?.let { it * 60_000L },
        poster = art.poster?.let { artworkUrl(if (art.posterIsShows) show!!.id else record.id, "poster", it) },
        backdrop = art.backdrop?.let { artworkUrl(if (art.backdropIsShows) show!!.id else record.id, "backdrop", it) },
        addedAt = record.addedAt,
        genres = text.genres, contentRating = text.contentRating, rating = text.rating,
        videoHeight = video?.height, hdr = video?.transfer != null,
        extra = record.details?.extra?.takeIf { record.kind == ItemKind.EXTRA },
        tmdbId = ids.tmdb, tvdbId = ids.tvdb, imdbId = ids.imdb,
    )
    return View(record, item, text.tagline, text.studio, text.cast, text.directors, art.poster, art.backdrop)
}

/**
 * The ids that name [record]: its own from the name or the `.nfo`, then, for a movie or show, the ones its TMDB
 * [lookup] found. A season's or episode's lookup holds its show's TMDB id, so it lends none.
 */
private fun providerIds(record: LibraryItemRecord, lookup: MetadataRecord?): ProviderIds {
    val own = record.details?.let { ProviderIds(it.tmdbId, it.tvdbId, it.imdbId) } ?: ProviderIds()
    val found = lookup?.takeIf { record.kind == ItemKind.MOVIE || record.kind == ItemKind.SHOW }
        ?.let { ProviderIds(it.tmdbId, it.tvdbId, it.imdbId) }
    return own.or(found)
}

/** An item's text: with a `.nfo` beside its file, what the `.nfo` says wins; else TMDB's, else the file's own. */
private class ItemText(record: LibraryItemRecord, facts: MetadataFacts?) {
    private val details = record.details
    private val nfo = details?.nfo == true

    val title = if (nfo) record.title else facts?.title ?: record.title
    val tagline = first(details?.tagline, facts?.tagline)
    val studio = first(details?.studio, facts?.studio)
    val contentRating = first(details?.contentRating, facts?.contentRating)
    val rating = first(details?.rating, facts?.rating)
    val genres = first(details?.genres, facts?.genres)
    val cast = first(details?.cast, facts?.cast)
    val directors = first(details?.directors, facts?.directors)

    private fun <T> first(own: T?, tmdb: T?): T? = if (nfo && own != null) own else tmdb ?: own

    private fun first(own: List<String>?, tmdb: List<String>?): List<String> =
        if (nfo && !own.isNullOrEmpty()) own else tmdb.takeUnless { it.isNullOrEmpty() } ?: own.orEmpty()
}

/** Where an item's poster and backdrop come from: its own files, then TMDB's, then its show's, then a frame. */
private class ItemArt(root: Path, record: LibraryItemRecord, facts: MetadataFacts?, showView: View?) {
    /**
     * A file with no picture of its own shows a frame from it, as an episode's or a video's still and a movie's
     * backdrop.
     */
    private val frame = record.path?.takeIf { record.media?.video != null && record.kind in FRAMED }?.let {
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

    val backdrop = record.backdrop?.let { ArtSource.Local(root, it) }
        ?: facts?.backdrop?.let { ArtSource.Tmdb(it, "w1280") }
        ?: showView?.backdrop
        ?: frame

    /** A season showing its show's poster serves it from the show, so both share one image. */
    val posterIsShows = poster != null && poster == showView?.poster && record.kind == ItemKind.SEASON
    val backdropIsShows = backdrop != null && backdrop == showView?.backdrop
}
