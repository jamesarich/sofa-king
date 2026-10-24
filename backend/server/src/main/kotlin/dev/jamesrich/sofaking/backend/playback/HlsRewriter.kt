package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.httpUri
import java.net.URI

object HlsRewriter {
    private val uriAttribute = Regex("(?<![A-Z-])URI=\"([^\"]+)\"")
    suspend fun rewrite(text: String, base: URI, path: suspend (String) -> String): String {
        if (!text.trimStart().startsWith("#EXTM3U")) {
            throw ApiFailure(502, "INVALID_MANIFEST", "Invalid HLS manifest", true)
        }
        val output = StringBuilder()
        for (line in text.lineSequence()) {
            if (line.startsWith("#EXT-X-DEFINE") || line.startsWith("#EXT-X-CONTENT-STEERING")) {
                throw ApiFailure(422, "FORMAT_UNSUPPORTED", "HLS variables and content steering are not supported")
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
        if (reference.contains("{$")) throw ApiFailure(422, "FORMAT_UNSUPPORTED", "HLS variables are not supported")
        val resolved = base.resolve(reference).toString()
        try {
            httpUri(resolved)
        } catch (_: Exception) {
            throw ApiFailure(422, "FORMAT_UNSUPPORTED", "Only HTTP(S) HLS resources are supported")
        }
        return resolved
    }
}
