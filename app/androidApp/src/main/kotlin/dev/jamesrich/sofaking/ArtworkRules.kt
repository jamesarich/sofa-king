package dev.jamesrich.sofaking

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException

/** The largest artwork image the provider caches. */
internal const val MAX_ARTWORK_BYTES = 10L * 1024 * 1024

/** Suffix of a download in progress; [staleArtwork] never names one, since a fetch may still be writing it. */
internal const val PARTIAL_SUFFIX = ".part"

/** `scheme://host[:port]` of an http or https [url], lowercase and without a default port; null for any other. */
internal fun originOf(url: String): String? {
    val uri = try {
        URI(url.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    val defaultPort = when (scheme) {
        "http" -> HTTP_PORT
        "https" -> HTTPS_PORT
        else -> return null
    }
    if (host == null || uri.rawUserInfo != null) return null
    return if (uri.port == -1 || uri.port == defaultPort) "$scheme://$host" else "$scheme://$host:${uri.port}"
}

/** Whether [artworkUrl] is on the configured server's origin; the provider fetches from no other host. */
internal fun fromServer(serverUrl: String?, artworkUrl: String): Boolean {
    val server = serverUrl?.let(::originOf) ?: return false
    return server == originOf(artworkUrl)
}

/** Whether [contentType] names an image. */
internal fun isImage(contentType: String?): Boolean = contentType?.trim()?.lowercase()?.startsWith("image/") == true

/** Why a response is not cached, or null when it is an image of an allowed size. */
internal fun refusal(status: Int, contentType: String?, length: Long): String? = when {
    status != HTTP_OK -> "HTTP $status"
    !isImage(contentType) -> "not an image"
    length > MAX_ARTWORK_BYTES -> "too large"
    else -> null
}

/** The cached files in [names] to delete: those not in [keep], leaving downloads in progress alone. */
internal fun staleArtwork(names: Collection<String>, keep: Set<String>): List<String> =
    names.filter { it !in keep && !it.endsWith(PARTIAL_SUFFIX) }

/** Copies [input] to [output], failing once more than [limit] bytes have come. */
@Throws(IOException::class)
internal fun copyLimited(input: InputStream, output: OutputStream, limit: Long = MAX_ARTWORK_BYTES) {
    val buffer = ByteArray(COPY_BUFFER)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        total += read
        if (total > limit) throw IOException("artwork is larger than $limit bytes")
        output.write(buffer, 0, read)
    }
}

private const val COPY_BUFFER = 8 * 1024
private const val HTTP_OK = 200
private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443
