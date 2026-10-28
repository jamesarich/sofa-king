package dev.jamesrich.sofaking.data

import io.ktor.client.request.post
import io.ktor.client.request.put

// The endpoints of [SofaKingApi] for the household's taste in music.

/** Likes or unlikes the song, album or artist [id]. */
internal suspend fun SofaKingApi.updateFavorite(id: String, favorite: Boolean): LibraryItemDto = call {
    http.put(itemPath(id) + "/favorite") { json(UpdateFavoriteDto(favorite)) }.decode()
}

/** Counts one play of the song [id]. */
internal suspend fun SofaKingApi.recordPlay(id: String): LibraryItemDto = call {
    http.post(itemPath(id) + "/played").decode()
}
