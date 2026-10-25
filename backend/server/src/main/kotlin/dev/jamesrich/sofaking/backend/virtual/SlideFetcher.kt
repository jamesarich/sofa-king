package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.logos.LogoCache
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fetches a break's slide, an image URL, as it is when the slide airs, through [policy] like any upstream request, so
 * FFmpeg reads a local file. Only PNG, JPEG, GIF and WebP images of at most [maxBytes] are kept.
 */
class SlideFetcher(
    private val http: HttpClient,
    private val policy: UpstreamPolicy,
    private val maxBytes: Int = MAX_BYTES,
) {
    private val log = LoggerFactory.getLogger(SlideFetcher::class.java)

    /** Saves [url]'s image to [file]; false when it cannot be fetched or is not an image. */
    suspend fun save(url: String, file: Path): Boolean = runCatchingCancellable {
        val bytes = http.withUpstreamResponse(
            url,
            url,
            emptyMap(),
            requestTimeoutMillis = TIMEOUT_MS,
            policy = policy,
        ) {
            require(it.status == HttpStatusCode.OK) { "Slide fetch answered ${it.status.value}" }
            it.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { length -> require(length <= maxBytes) }
            it.bodyAsChannel().readBuffer(maxBytes.toLong() + 1).readByteArray()
        }
        require(bytes.size <= maxBytes && LogoCache.rasterType(bytes) != null) { "Slide is not an image" }
        withContext(Dispatchers.IO) { Files.write(file, bytes) }
        true
    }.getOrElse {
        // Messages can carry the URL, which may hold a token; only the class is logged.
        log.warn("virtual slide not fetched class={}", it.javaClass.simpleName)
        false
    }

    private companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        const val TIMEOUT_MS = 10_000L
    }
}

/** The airing ID of a break's slide number [index], as a schedule stores it in place of an item. */
internal fun slideAiringId(index: Int): String = SLIDE_PREFIX + index

/** The number of the slide an airing shows, or null when it shows no slide. */
internal fun slideOf(itemId: String?): Int? = itemId?.takeIf { it.startsWith(SLIDE_PREFIX) }
    ?.removePrefix(SLIDE_PREFIX)?.toIntOrNull()

private const val SLIDE_PREFIX = "slide:"
