package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.MIB
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.inflateHead
import dev.jamesrich.sofaking.backend.upstream.isGzip
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Declares a channel's CEA-608 captions to players that show only declared ones. AVFoundation shows the captions in a
 * live stream only when a multivariant playlist names them with `CLOSED-CAPTIONS`, and many sources send a bare media
 * playlist, or a multivariant one that names none. So for those players the root playlist of a source whose segments
 * carry 608 data names CC1: a multivariant playlist gets the caption group, and a media playlist goes out inside a
 * multivariant playlist of its own. Media3, libVLC and hls.js find the captions in the video without being told.
 *
 * Whether a source carries captions comes from reading its newest segment (see [CaptionScan]) when its root playlist
 * is first asked for; the answer holds for a day when it found captions and for ten minutes when it did not, since a
 * break between programs can carry none.
 */
internal class CaptionPlaylists(
    private val http: HttpClient,
    private val policy: UpstreamPolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    private class Seen(val captions: Boolean, val at: Instant)

    private val seen = ConcurrentHashMap<String, Seen>()

    /**
     * [text], the root playlist of source [offeringId] from [source], with its captions declared; null when it needs
     * nothing: it names its captions itself, or the source carries none, or the check failed.
     */
    suspend fun declared(offeringId: String, text: String, source: URI, stream: ResolvedStream): String? = when {
        isMultivariant(text) -> withCaptionGroup(text)?.takeIf { carries(offeringId, text, source, stream) }
        text.contains("#EXTINF") -> captionedMaster(source).takeIf { carries(offeringId, text, source, stream) }
        else -> null
    }

    private suspend fun carries(offeringId: String, text: String, source: URI, stream: ResolvedStream): Boolean {
        val now = clock.instant()
        val known = seen[offeringId]?.takeIf {
            it.at.plus(if (it.captions) FOUND_HOLDS else MISSING_HOLDS).isAfter(now)
        }
        if (known != null) return known.captions
        // A check that fails or runs long tells nothing; the next tune asks again.
        val found = withTimeoutOrNull(CHECK_TIMEOUT.toMillis()) {
            runCatchingCancellable { check(text, source, stream) }.getOrNull()
        }
        if (found != null) seen[offeringId] = Seen(found, now)
        return found == true
    }

    /** Reads the newest segment of [text]'s media playlist, the first variant's for a multivariant one. */
    private suspend fun check(text: String, source: URI, stream: ResolvedStream): Boolean {
        val media = if (isMultivariant(text)) {
            val variant = firstVariant(text, source) ?: return false
            fetch(variant, stream) { response -> playlist(response) to URI(response.request.url.toString()) }
        } else {
            text to source
        }
        val segment = newestSegment(media.first, media.second) ?: return false
        return fetch(segment, stream) { response ->
            val body = response.bodyAsChannel()
            val scan = CaptionScan()
            val buffer = ByteArray(READ)
            var read = 0L
            while (!scan.found && read < SEGMENT_LIMIT) {
                val count = body.readAvailable(buffer)
                if (count < 0) break
                scan.feed(buffer, 0, count)
                read += count
            }
            scan.found
        }
    }

    private suspend fun <T> fetch(url: String, stream: ResolvedStream, read: suspend (HttpResponse) -> T): T =
        http.withUpstreamResponse(url, stream.url, stream.headers, unbounded = true, policy = policy) { response ->
            if (response.status.value !in OK_STATUSES) error("The source answered ${response.status.value}")
            read(response)
        }

    private suspend fun playlist(response: HttpResponse): String {
        val raw = response.bodyAsChannel().readBuffer(PLAYLIST_LIMIT.toLong()).readByteArray()
        return (if (isGzip(raw)) inflateHead(raw, PLAYLIST_LIMIT) else raw).toString(Charsets.UTF_8)
    }

    companion object {
        /**
         * The caption group a declared playlist names: CC1, the one broadcasts carry the main captions in. It is named
         * English, as CEA-608 CC1 nearly always is: the Apple TV's Subtitles panel cannot turn on captions without a
         * language until something else has selected them.
         */
        const val GROUP = "sofa-cc"

        /**
         * What the variant of a [captionedMaster] carries on the media playlist's URL, so that its token reads the
         * media playlist and not the root again. Fragments go no further than the server.
         */
        const val MEDIA = "#sofa-captioned-media"

        private const val MEDIA_TAG =
            "#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID=\"$GROUP\",NAME=\"CC1\",LANGUAGE=\"en\"," +
                "INSTREAM-ID=\"CC1\",DEFAULT=NO,AUTOSELECT=YES"

        /** What the one variant of a [captionedMaster] says it needs; a single variant is never switched from. */
        private const val BANDWIDTH = 8_000_000

        private val FOUND_HOLDS = Duration.ofDays(1)
        private val MISSING_HOLDS = Duration.ofMinutes(10)
        private val CHECK_TIMEOUT = Duration.ofSeconds(3)
        private const val PLAYLIST_LIMIT = 2 * MIB
        private const val SEGMENT_LIMIT = 16L * MIB
        private const val READ = 64 * 1024
        private val OK_STATUSES = setOf(200, 206)

        fun isMultivariant(text: String): Boolean = text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

        /** A multivariant playlist naming one variant, [media] (marked [MEDIA]), with CC1 in it. */
        fun captionedMaster(media: URI): String =
            "#EXTM3U\n$MEDIA_TAG\n#EXT-X-STREAM-INF:BANDWIDTH=$BANDWIDTH,CLOSED-CAPTIONS=\"$GROUP\"\n$media$MEDIA\n"

        /**
         * Multivariant [text] with CC1 named on every variant; null when a variant names its captions already, or
         * that it has none (`CLOSED-CAPTIONS=NONE`).
         */
        fun withCaptionGroup(text: String): String? {
            val lines = text.lines()
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") && it.contains("CLOSED-CAPTIONS=") }) return null
            val output = ArrayList<String>(lines.size + 1)
            var named = false
            for (line in lines) {
                if (!line.startsWith("#EXT-X-STREAM-INF")) {
                    output += line
                    continue
                }
                if (!named) output += MEDIA_TAG
                named = true
                output += "$line,CLOSED-CAPTIONS=\"$GROUP\""
            }
            return output.joinToString("\n")
        }

        private fun firstVariant(text: String, source: URI): String? {
            val lines = text.lines()
            val tag = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val uri = lines.drop(tag + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return null
            return source.resolve(playlistUri(uri.trim())).toString()
        }

        private fun newestSegment(text: String, source: URI): String? {
            val uri = text.lines().lastOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return null
            return source.resolve(playlistUri(uri.trim())).toString()
        }
    }
}
