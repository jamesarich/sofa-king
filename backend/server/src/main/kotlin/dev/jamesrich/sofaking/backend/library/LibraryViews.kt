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

/** Every present item of every library as the store holds them now. */
internal suspend fun buildSnapshot(store: BackendStore, revision: Long): Snapshot {
    val libraries = store.libraries()
    val records = libraries.flatMap { library ->
        store.libraryItems(library.id).filter { it.missingSince == null }.map { library to it }
    }
    val facts = store.libraryMetadata(records.map { it.second.id })
    val shows = showsOf(records.map { it.second })
    val views = LinkedHashMap<String, View>()
    // Shows first, so a season or episode can borrow its show's artwork.
    for ((library, record) in records.sortedBy { if (it.second.kind == ItemKind.SHOW) 0 else 1 }) {
        val show = shows[record.id]?.takeIf { it.id != record.id }
        views[record.id] = itemView(library, record, facts[record.id]?.facts, show, show?.let { views[it.id] })
    }
    return arranged(revision, libraries, views, shows)
}

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
    val children = held.groupBy { it.record.parentId!! }.mapValues { (_, list) -> list.sortedWith(PLAY_ORDER) }
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
    )
}

/** [record] of [library] as clients read it, with what TMDB found ([facts]) and, for an episode or season, its show. */
private fun itemView(
    library: LibraryRecord,
    record: LibraryItemRecord,
    facts: MetadataFacts?,
    show: LibraryItemRecord?,
    showView: View?,
): View {
    val text = ItemText(record, facts)
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
    )
    return View(record, item, text.tagline, text.studio, text.cast, text.directors, art.poster, art.backdrop)
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
