package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.api.PlaybackSession
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The lineup for third-party players under `/output`: an M3U playlist, an XMLTV guide and one stream URL per
 * channel. Players send no headers, so the token travels in the query and the generated URLs carry it.
 */
fun Route.outputRoutes(runtime: LiveRuntime, clock: Clock, config: OutputConfig, apiToken: String?) {
    route("/output") {
        get("/playlist.m3u") {
            authorizeOutput(call, config.token, apiToken)
            val base = publicBase(call)
            val query = tokenQuery(config.token)
            val channels = runtime.catalog.catalog().channels
            val entries = runtime.lineup.entries().associateBy { it.id }
            val preferred = runtime.sessions.preferredOfferings(channels.map { it.id })
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondTextWriter(ContentType.parse("audio/x-mpegurl").withCharset(Charsets.UTF_8)) {
                write("#EXTM3U url-tvg=\"$base/output/guide.xml$query\" x-tvg-url=\"$base/output/guide.xml$query\"\n")
                for (channel in channels) {
                    // Players infer the stream type from the suffix; a channel with no eligible source lists as TS and fails at play time.
                    val suffix = if (preferred[channel.id]?.protocol == "hls") "m3u8" else "ts"
                    write("#EXTINF:-1 tvg-id=\"${channel.id}\" tvg-chno=\"${attribute(channel.displayNumber)}\" tvg-name=\"${attribute(channel.name)}\"")
                    channel.logo?.let { write(" tvg-logo=\"${attribute(it)}\"") }
                    entries[channel.id]?.group?.let { write(" group-title=\"${attribute(it)}\"") }
                    write(",${attribute(channel.name)}\n$base/output/stream/${channel.id}.$suffix$query\n")
                }
            }
        }
        get("/guide.xml") {
            authorizeOutput(call, config.token, apiToken)
            val days = call.request.queryParameters["days"]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..14 } ?: throw ApiFailure(400, "INVALID_QUERY", "days must be between 1 and 14")
            } ?: config.guideDays
            val now = clock.instant()
            val from = now.minus(Duration.ofHours(3))
            val to = now.plus(Duration.ofDays(days.toLong()))
            val channels = runtime.catalog.catalog().channels
            val mappings = channels.mapNotNull { channel -> runtime.lineup.entry(channel.id)?.guide?.let { channel.id to it } }
            val programmes = HashMap<String, List<GuideProgramme>>()
            for ((guideId, group) in mappings.groupBy { it.second.guideId }) {
                val rows = runtime.guides.programmes(guideId, group.map { it.second.channelId }, from, to)
                for ((entryId, mapping) in group) programmes[entryId] = rows[mapping.channelId].orEmpty()
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondTextWriter(ContentType.Application.Xml.withCharset(Charsets.UTF_8)) {
                write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"sofa-king-backend\">\n")
                for (channel in channels) {
                    write("<channel id=\"${xml(channel.id)}\"><display-name>${xml(channel.name)}</display-name><display-name>${xml(channel.displayNumber)}</display-name>")
                    channel.logo?.let { write("<icon src=\"${xml(it)}\"/>") }
                    write("</channel>\n")
                }
                for (channel in channels) {
                    for (programme in programmes[channel.id].orEmpty()) {
                        write("<programme start=\"${xmltvTime(programme.startsAt)}\" stop=\"${xmltvTime(programme.endsAt)}\" channel=\"${xml(channel.id)}\">")
                        write("<title>${xml(programme.title)}</title>")
                        programme.description?.let { write("<desc>${xml(it)}</desc>") }
                        write("</programme>\n")
                    }
                }
                write("</tv>\n")
            }
        }
        get("/stream/{id}") {
            authorizeOutput(call, config.token, apiToken)
            val id = call.parameters["id"]!!.substringBefore('.')
            val entry = runtime.lineup.entry(id)?.takeIf { it.enabled } ?: throw ApiFailure(404, "CHANNEL_NOT_FOUND", "Unknown channel")
            val base = publicBase(call)
            serveExternal(call, runtime, entry.id, shareHls = true) { session ->
                call.respondRedirect(base + session.descriptor.url)
                true
            }
        }
    }
}

/**
 * Plays [channelId] for a player that only speaks HTTP. A TS source is fanned out through this response and its
 * session ends with it. An HLS source goes to [hls], which returns true when the player's own media requests now
 * hold the session (a redirect) and false when it was consumed inside this request (a remux). A protocol detected
 * on the first fetch switches paths here instead of reaching the player as 410.
 */
internal suspend fun serveExternal(
    call: ApplicationCall,
    runtime: LiveRuntime,
    channelId: String,
    shareHls: Boolean,
    hls: suspend (PlaybackSession) -> Boolean,
) {
    val sessions = runtime.sessions
    if (shareHls) sessions.externalFor(channelId)?.let { hls(it); return }
    val opened = sessions.openExternal(channelId)
    var release = true
    try {
        var current = opened
        for (attempt in 0..1) {
            if (current.descriptor.protocol == "hls") {
                release = !hls(current)
                return
            }
            try {
                runtime.relay.serve(call, current.id, current.descriptor.epoch, current.descriptor.url.substringAfterLast('/'))
                return
            } catch (e: ApiFailure) {
                if (e.code != "STREAM_CHANGED" || attempt == 1) throw e
            }
            current = sessions.get(current.id)
        }
    } finally {
        if (release) withContext(NonCancellable) { sessions.release(opened.id) }
    }
}

/** The output token in the query or as a bearer, or the API token as a bearer; nothing when neither is configured. */
internal fun authorizeOutput(call: ApplicationCall, outputToken: String?, apiToken: String?) {
    if (outputToken == null && apiToken == null) return
    val bearer = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
    val query = call.request.queryParameters["token"]
    val authorized = (outputToken != null && (same(query, outputToken) || same(bearer, outputToken))) || (apiToken != null && same(bearer, apiToken))
    if (!authorized) throw ApiFailure(401, "AUTH_REQUIRED", "Output token required")
}

private fun same(presented: String?, expected: String): Boolean =
    presented != null && MessageDigest.isEqual(expected.toByteArray(), presented.toByteArray())

/** The address the player reached us by, so generated URLs work through port mappings; `X-Forwarded-Proto` names a TLS proxy. */
internal fun publicBase(call: ApplicationCall): String {
    val host = call.request.headers[HttpHeaders.Host]?.takeIf { it.matches(Regex("[A-Za-z0-9.\\-\\[\\]:]{1,253}")) }
        ?: "${call.request.origin.localAddress}:${call.request.origin.localPort}"
    val scheme = call.request.headers["X-Forwarded-Proto"]?.takeIf { it == "https" } ?: call.request.origin.scheme
    return "$scheme://$host"
}

internal fun tokenQuery(token: String?): String = token?.let { "?token=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""

private fun attribute(value: String): String = value.replace('"', '\'').replace(Regex("[\\r\\n]"), " ").trim()

private val xmltvFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

private fun xmltvTime(instant: Instant): String = xmltvFormat.format(instant) + " +0000"

internal fun xml(value: String): String = buildString(value.length) {
    for (char in value) {
        when {
            char == '&' -> append("&amp;")
            char == '<' -> append("&lt;")
            char == '>' -> append("&gt;")
            char == '"' -> append("&quot;")
            char < ' ' && char != '\t' && char != '\n' && char != '\r' -> Unit
            else -> append(char)
        }
    }
}
