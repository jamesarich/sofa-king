package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlin.time.Instant

/**
 * The server's media libraries as the app's models. Artwork paths are resolved against the server address, as
 * logos are, and fetched without the token.
 */
class LibraryRepository(internal val api: SofaKingApi) {
    suspend fun home(): MediaHome = api.libraryHome().let { dto ->
        MediaHome(
            shelves = dto.shelves.map {
                MediaShelf(it.id, it.title, it.items.map(api::mediaItem), it.libraryId)
            }.filter { it.items.isNotEmpty() },
            libraries = dto.libraries.map(::mediaLibrary),
        )
    }

    suspend fun page(
        libraryId: String,
        sort: LibrarySort,
        unwatched: Boolean,
        cursor: String? = null,
        limit: Int = PAGE_SIZE,
        genre: String? = null,
        view: MusicView = MusicView.ARTISTS,
    ): MediaPage = api.mediaPage(
        api.libraryItems(libraryId, sort.wire, unwatched, cursor, limit, genre, view.wire, view.favorites),
    )

    /** The songs of a radio from the artist, album or song [id], in the order to play them. */
    suspend fun radio(id: String, limit: Int = RADIO_SIZE): List<MediaItem> =
        api.libraryRadio(id, limit).items.map { api.mediaItem(it) }

    suspend fun search(query: String): SearchResults = api.librarySearch(query).let { dto ->
        SearchResults(dto.items.map { api.mediaItem(it) }, dto.people)
    }

    suspend fun detail(id: String): MediaItemDetail = api.libraryItem(id).let { dto ->
        MediaItemDetail(
            item = api.mediaItem(dto.item),
            children = dto.children.map(api::mediaItem),
            next = dto.next?.let(api::mediaItem),
            tagline = dto.tagline,
            studio = dto.studio,
            cast = dto.cast,
            directors = dto.directors,
            audio = dto.audio.map(::mediaTrack),
            subtitles = dto.subtitles.map(::mediaTrack),
            container = dto.container,
            videoCodec = dto.videoCodec,
            intro = introSpan(dto.segments, Wire.SegmentKind.INTRO),
            credits = introSpan(dto.segments, Wire.SegmentKind.CREDITS),
            scrub = dto.scrub?.let {
                ScrubTiles(
                    it.intervalMs,
                    it.tileWidth,
                    it.tileHeight,
                    it.columns,
                    it.rows,
                    it.frames,
                    it.sheets.mapNotNull(api::logoUrl),
                )
            },
            extras = dto.extras.map(api::mediaItem),
            related = dto.related.map(api::mediaItem),
            popular = dto.popular.map(api::mediaItem),
        )
    }

    /** Text subtitle track [index] of [id], with its cues in the item's time. */
    suspend fun subtitles(id: String, index: Int): Subtitles = parseWebVtt(api.librarySubtitles(id, index))

    /** The song [id]'s lyrics; null when it has none. */
    suspend fun lyrics(id: String): Lyrics? = api.libraryLyrics(id)?.let(::parseLrc)?.takeIf { it.lines.isNotEmpty() }

    /** Saves [positionMs] into [id]; the server marks it watched past 90% and clears a position under 5%. */
    suspend fun savePosition(id: String, positionMs: Long, durationMs: Long?): MediaItem = api.mediaItem(
        api.updatePlayState(
            id,
            UpdatePlayStateDto(positionMs = positionMs.coerceAtLeast(0), durationMs = durationMs),
        ),
    )

    /** Marks [id] watched or not; a show or season marks every episode in it. */
    suspend fun setWatched(id: String, watched: Boolean): MediaItem =
        api.mediaItem(api.updatePlayState(id, UpdatePlayStateDto(watched = watched)))

    /** Takes [id] off Continue watching for the household until it is played again. */
    suspend fun removeFromContinue(id: String): MediaItem =
        api.mediaItem(api.updatePlayState(id, UpdatePlayStateDto(dismissed = true)))

    companion object {
        /** Items per grid page: a few screens of posters. */
        const val PAGE_SIZE = 60

        /** Songs in a radio. */
        const val RADIO_SIZE = 50
    }
}

private fun SofaKingApi.mediaPage(dto: LibraryPageDto) = MediaPage(
    dto.items.map { mediaItem(it) },
    dto.total,
    dto.next,
    dto.genres,
    dto.letters?.let { runs ->
        letterJumps(
            runs.map {
                it.letter to
                    it.count
            },
        )
    },
)

private fun mediaLibrary(dto: LibraryDto) = MediaLibrary(dto.id, dto.name, LibraryKind.fromWire(dto.kind), dto.items)

/** The first span of [kind] among [segments] that has a length. */
private fun introSpan(segments: List<MediaSegmentDto>, kind: String) =
    segments.firstOrNull { it.kind == kind && it.endMs > it.startMs }?.let { IntroSpan(it.startMs, it.endMs) }

private fun mediaTrack(dto: MediaTrackDto) =
    MediaTrackInfo(dto.index, dto.codec, dto.language, dto.title, dto.channels, dto.default, dto.forced)

internal fun SofaKingApi.mediaItem(dto: LibraryItemDto) = MediaItem(
    id = dto.id,
    libraryId = dto.libraryId,
    kind = ItemKind.fromWire(dto.kind),
    title = dto.title,
    parentId = dto.parentId,
    showId = dto.showId,
    showTitle = dto.showTitle,
    year = dto.year,
    season = dto.season,
    episode = dto.episode,
    episodeEnd = dto.episodeEnd,
    aired = dto.aired,
    overview = dto.overview?.trim()?.takeIf { it.isNotEmpty() },
    durationMs = dto.durationMs,
    poster = dto.poster?.let(::logoUrl),
    backdrop = dto.backdrop?.let(::logoUrl),
    addedAt = Instant.parseOrNull(dto.addedAt),
    genres = dto.genres,
    contentRating = dto.contentRating,
    rating = dto.rating,
    videoHeight = dto.videoHeight,
    hdr = dto.hdr,
    children = dto.children,
    unwatched = dto.unwatched,
    progress = dto.playState?.let { PlayProgress(it.positionMs, it.durationMs, it.watched) },
    extra = dto.extra?.let(ExtraKind::fromWire) ?: ExtraKind.OTHER.takeIf { dto.kind == Wire.ItemKind.EXTRA },
    artistId = dto.artistId,
    artist = dto.artist,
    trackArtist = dto.trackArtist,
    album = dto.album,
    track = dto.track,
    disc = dto.disc,
    favorite = dto.favorite,
    plays = dto.plays,
)
