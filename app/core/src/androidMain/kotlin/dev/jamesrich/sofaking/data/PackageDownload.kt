package dev.jamesrich.sofaking.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Long enough for a package over a slow TV connection; the client's socket timeout still ends a stalled one. */
private const val DOWNLOAD_TIMEOUT_MS = 10 * 60_000L

/**
 * Downloads [url] into [file], replacing it, and returns the SHA-256 of what arrived in lowercase hex. [onProgress]
 * gets each new whole percent, or null when the response gives no length. Transport failures and an unsuccessful
 * status throw [IOException]; a failed download leaves no file behind.
 */
internal suspend fun downloadPackage(http: HttpClient, url: String, file: File, onProgress: (Int?) -> Unit): String =
    withContext(ioDispatcher) {
        runCatching {
            http.prepareGet(url) { timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS } }.execute { response ->
                if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value}")
                val total = response.contentLength()?.takeIf { it > 0 }
                val digest = MessageDigest.getInstance("SHA-256")
                val body = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var received = 0L
                var shown = -1
                onProgress(if (total == null) null else 0)
                file.outputStream().use { out ->
                    while (true) {
                        val read = body.readAvailable(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        val percent = total?.let { (received * 100 / it).toInt().coerceAtMost(100) }
                        if (percent != null && percent != shown) {
                            shown = percent
                            onProgress(percent)
                        }
                    }
                }
                if (total != null && received != total) throw IOException("got $received of $total bytes")
                digest.digest().toHexString()
            }
        }.onFailure { file.delete() }.getOrThrow()
    }
