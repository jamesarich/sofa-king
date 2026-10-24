package dev.jamesrich.sofaking.data

import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart

// The media library endpoints of [SofaKingApi]: the home, grids, items and their subtitles, play state and VOD
// sessions.

/**
 * One page of [libraryId]'s top-level items in [sort] order (`title`, `added`, `year`, `rating` or `watched`), only
 * those in [genre] when given, from [cursor].
 */
internal suspend fun SofaKingApi.libraryItems(
    libraryId: String,
    sort: String,
    unwatched: Boolean,
    cursor: String?,
    limit: Int,
    genre: String? = null,
): LibraryPageDto = call {
    http.get("libraries/" + libraryId.encodeURLPathPart() + "/items") {
        url.parameters.append("sort", sort)
        url.parameters.append("limit", limit.toString())
        if (unwatched) url.parameters.append("unwatched", "true")
        if (genre != null) url.parameters.append("genre", genre)
        if (cursor != null) url.parameters.append("cursor", cursor)
    }.decode()
}

internal suspend fun SofaKingApi.libraryHome(): LibraryHomeDto = call {
    http.get("library/home").decode()
}

internal suspend fun SofaKingApi.librarySearch(query: String): LibraryPageDto = call {
    http.get("library/search") { url.parameters.append("q", query) }.decode()
}

internal suspend fun SofaKingApi.libraryItem(id: String): LibraryItemDetailDto = call {
    http.get(itemPath(id)).decode()
}

/**
 * Text subtitle track [index] of the item [id] as WebVTT. The server reads the whole file the first time, so the
 * request may take minutes.
 */
internal suspend fun SofaKingApi.librarySubtitles(id: String, index: Int): String = call {
    http.get(itemPath(id) + "/subtitles/$index") {
        timeout {
            requestTimeoutMillis = SUBTITLE_TIMEOUT_MS
            socketTimeoutMillis = SUBTITLE_TIMEOUT_MS
        }
    }.let { response ->
        response.checkSuccess()
        response.bodyAsText()
    }
}

/**
 * Saves where the household stopped in [id], or marks it (a show or season: every episode in it) watched or not.
 */
internal suspend fun SofaKingApi.updatePlayState(id: String, update: UpdatePlayStateDto): LibraryItemDto = call {
    http.put(itemPath(id) + "/play-state") { json(update) }.decode()
}

internal suspend fun SofaKingApi.openVod(request: OpenVodPlaybackDto): VodSessionDto = call {
    http.post("vod-sessions") { json(request) }.decode()
}

/** Renews the lease, or with [startMs] restarts a converted stream there under a new epoch. */
internal suspend fun SofaKingApi.updateVod(id: String, startMs: Long?): VodSessionDto = call {
    http.patch(vodPath(id)) { json(UpdateVodPlaybackDto(startMs)) }.decode()
}

internal suspend fun SofaKingApi.releaseVod(id: String) = call {
    http.delete(vodPath(id)).checkSuccess()
}

private fun itemPath(id: String) = "library/items/" + id.encodeURLPathPart()

/** How long the first read of a subtitle track may take, as the server reads the whole file. */
private const val SUBTITLE_TIMEOUT_MS = 5 * 60_000L
