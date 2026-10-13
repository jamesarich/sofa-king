package dev.jamesrich.sofaking.data

import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Channel logos and programme images, fetched over the API's connection pool without the token. */
object ImageFetch {
    /** The server caps a cached logo at 2 MiB; programme images larger than that are not worth a TV's memory. */
    private const val MAX_BYTES = 2L shl 20

    /** The image's bytes, or null when it cannot be fetched or is over [MAX_BYTES]. */
    suspend fun bytes(url: String): ByteArray? = withContext(ioDispatcher) {
        try {
            val response = apiHttpClient.get(url)
            if (!response.status.isSuccess()) return@withContext null
            if ((response.contentLength() ?: 0L) > MAX_BYTES) return@withContext null
            response.readRawBytes().takeIf { it.size <= MAX_BYTES }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
