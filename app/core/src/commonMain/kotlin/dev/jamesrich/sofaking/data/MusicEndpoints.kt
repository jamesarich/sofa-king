package dev.jamesrich.sofaking.data

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

// The Music endpoints of [SofaKingApi]: a song's lyrics, the radios, and the household's taste.

/** At most [limit] songs to play on from the artist, album or song [id], in the order to play them. */
internal suspend fun SofaKingApi.libraryRadio(id: String, limit: Int): LibraryPageDto = call {
    http.get(itemPath(id) + "/radio") { url.parameters.append("limit", limit.toString()) }.decode()
}

/**
 * The lyrics of the song [id] as LRC text; null when it has none, or the server is too old to serve any. The server
 * may ask LRCLIB the first time, so the request may take seconds.
 */
internal suspend fun SofaKingApi.libraryLyrics(id: String): String? = call {
    val response = http.get(itemPath(id) + "/lyrics")
    if (response.status == HttpStatusCode.NotFound) return@call null
    response.checkSuccess()
    response.bodyAsText()
}

/** Likes or unlikes the song, album or artist [id]. */
internal suspend fun SofaKingApi.updateFavorite(id: String, favorite: Boolean): LibraryItemDto = call {
    http.put(itemPath(id) + "/favorite") { json(UpdateFavoriteDto(favorite)) }.decode()
}

/** Counts one play of the song [id]. */
internal suspend fun SofaKingApi.recordPlay(id: String): LibraryItemDto = call {
    http.post(itemPath(id) + "/played").decode()
}
