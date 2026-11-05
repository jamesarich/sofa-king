package dev.jamesrich.sofaking.data

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * The text at [url], which media requests answer without a token; null when the server did not answer with it.
 * Transport failures surface as exceptions.
 */
suspend fun fetchPlaylistText(url: String): String? {
    val response = mediaHttpClient.get(url)
    return if (response.status.isSuccess()) response.bodyAsText() else null
}
