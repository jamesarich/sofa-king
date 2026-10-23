package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlin.time.Instant

/**
 * The server's media libraries as the app's models. Artwork paths are resolved against the server address, as
 * logos are, and fetched without the token.
 */
class LibraryRepository(private val api: SofaKingApi) {
    suspend fun home(): MediaHome = api.libraryHome().let { dto ->
        MediaHome(
            shelves = dto.shelves.map {
                MediaShelf(it.id, it.title, it.items.map(::item), it.libraryId)
            }.filter { it.items.isNotEmpty() },
            libraries = dto.libraries.map(::library),
        )
    }

    suspend fun page(
        libraryId: String,
        sort: LibrarySort,
        unwatched: Boolean,
        cursor: String? = null,
        limit: Int = PAGE_SIZE,
        genre: String? = null,
    ): MediaPage = page(api.libraryItems(libraryId, sort.wire, unwatched, cursor, limit, genre))

    suspend fun search(query: String): List<MediaItem> = page(api.librarySearch(query)).items

    suspend fun detail(id: String): MediaItemDetail = api.libraryItem(id).let { dto ->
        MediaItemDetail(
            item = item(dto.item),
            children = dto.children.map(::item),
            next = dto.next?.let(::item),
            tagline = dto.tagline,
            studio = dto.studio,
            cast = dto.cast,
            directors = dto.directors,
            audio = dto.audio.map(::track),
            subtitles = dto.subtitles.map(::track),
            container = dto.container,
            videoCodec = dto.videoCodec,
            intro = segment(dto.segments, Wire.SegmentKind.INTRO),
            credits = segment(dto.segments, Wire.SegmentKind.CREDITS),
        )
    }

    /** Text subtitle track [index] of [id], with its cues in the item's time. */
    suspend fun subtitles(id: String, index: Int): Subtitles = parseWebVtt(api.librarySubtitles(id, index))

    /** Saves [positionMs] into [id]; the server marks it watched past 90% and clears a position under 5%. */
    suspend fun savePosition(id: String, positionMs: Long, durationMs: Long?): MediaItem = item(
        api.updatePlayState(
            id,
            UpdatePlayStateDto(positionMs = positionMs.coerceAtLeast(0), durationMs = durationMs),
        ),
    )

    /** Marks [id] watched or not; a show or season marks every episode in it. */
    suspend fun setWatched(id: String, watched: Boolean): MediaItem =
        item(api.updatePlayState(id, UpdatePlayStateDto(watched = watched)))

    private fun page(dto: LibraryPageDto) = MediaPage(
        dto.items.map(::item),
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

    private fun library(dto: LibraryDto) = MediaLibrary(dto.id, dto.name, LibraryKind.fromWire(dto.kind), dto.items)

    /** The first span of [kind] among [segments] that has a length. */
    private fun segment(segments: List<MediaSegmentDto>, kind: String) =
        segments.firstOrNull { it.kind == kind && it.endMs > it.startMs }?.let { IntroSpan(it.startMs, it.endMs) }

    private fun track(dto: MediaTrackDto) =
        MediaTrackInfo(dto.index, dto.codec, dto.language, dto.title, dto.channels, dto.default, dto.forced)

    private fun item(dto: LibraryItemDto) = MediaItem(
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
        poster = dto.poster?.let(api::logoUrl),
        backdrop = dto.backdrop?.let(api::logoUrl),
        addedAt = Instant.parseOrNull(dto.addedAt),
        genres = dto.genres,
        contentRating = dto.contentRating,
        rating = dto.rating,
        videoHeight = dto.videoHeight,
        hdr = dto.hdr,
        children = dto.children,
        unwatched = dto.unwatched,
        progress = dto.playState?.let { PlayProgress(it.positionMs, it.durationMs, it.watched) },
    )

    companion object {
        /** Items per grid page: a few screens of posters. */
        const val PAGE_SIZE = 60
    }
}
