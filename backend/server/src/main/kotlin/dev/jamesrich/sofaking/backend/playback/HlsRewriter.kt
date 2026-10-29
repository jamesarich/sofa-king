package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.httpUri
import dev.jamesrich.sofaking.backend.catalog.percentEncode
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.net.URISyntaxException

object HlsRewriter {
    private val uriAttribute = Regex("(?<![A-Z-])URI=\"([^\"]+)\"")
    suspend fun rewrite(text: String, base: URI, path: suspend (String) -> String): String {
        if (!text.trimStart().startsWith("#EXTM3U")) {
            throw ApiFailure(
                HttpStatusCode.BadGateway,
                "INVALID_MANIFEST",
                "This channel's stream couldn't be read",
                true,
            )
        }
        val output = StringBuilder()
        for (line in text.lineSequence()) {
            if (line.startsWith("#EXT-X-DEFINE") || line.startsWith("#EXT-X-CONTENT-STEERING")) {
                throw ApiFailure(
                    HttpStatusCode.UnprocessableEntity,
                    "FORMAT_UNSUPPORTED",
                    "HLS variables and content steering are not supported",
                )
            }
            if (line.isBlank()) {
                output.append('\n')
                continue
            }
            if (!line.startsWith('#')) {
                output.append(path(resolve(base, line.trim())))
            } else {
                var offset = 0
                for (match in uriAttribute.findAll(line)) {
                    output.append(line.substring(offset, match.range.first))
                    output.append("URI=\"").append(path(resolve(base, match.groupValues[1]))).append('"')
                    offset = match.range.last + 1
                }
                output.append(line.substring(offset))
            }
            output.append('\n')
        }
        return output.toString()
    }
    private fun resolve(base: URI, reference: String): String {
        if (reference.contains(
                "{$",
            )
        ) {
            throw ApiFailure(
                HttpStatusCode.UnprocessableEntity,
                "FORMAT_UNSUPPORTED",
                "This channel's stream couldn't be read",
            )
        }
        return try {
            base.resolve(playlistUri(reference)).toString().also(::httpUri)
        } catch (_: Exception) {
            throw ApiFailure(
                HttpStatusCode.UnprocessableEntity,
                "FORMAT_UNSUPPORTED",
                "Only HTTP(S) HLS resources are supported",
            )
        }
    }
}

/**
 * A [reference] a playlist names, as a URI. Players fetch references that carry characters a URI may not, such as the
 * brackets in Wurl's `sub-subtitles-English[VTT].m3u8`, so one that does not parse as it is gets them
 * percent-encoded, brackets included past the host.
 */
internal fun playlistUri(reference: String): URI = try {
    URI(reference)
} catch (_: URISyntaxException) {
    val encoded = percentEncode(reference)
    val path = authorityEnd(encoded)
    URI(encoded.take(path) + encoded.drop(path).replace("[", "%5B").replace("]", "%5D"))
}

/** Where the authority of [reference] ends: 0 for a reference without one, so brackets in an IPv6 host stay. */
private fun authorityEnd(reference: String): Int {
    val start = SCHEME_AUTHORITY.find(reference)?.range?.last?.plus(1) ?: return 0
    return reference.indexOfAny(charArrayOf('/', '?', '#'), start).takeIf { it >= 0 } ?: reference.length
}

/** The start of a reference up to its authority: `scheme://` or a scheme-relative `//`. */
private val SCHEME_AUTHORITY = Regex("^([A-Za-z][A-Za-z0-9+.-]*:)?//")
