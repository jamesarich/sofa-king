package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.admin.sameSecret
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.contract.PlaybackSession
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The lineup for third-party players under `/output`: an M3U playlist, an XMLTV guide and one stream URL per
 * channel. Players send no headers, so the token travels in the query and the generated URLs carry it.
 */
fun Route.outputRoutes(runtime: LiveRuntime, clock: Clock, config: OutputConfig, apiToken: String?) {
    route("/output") {
        get("/playlist.m3u") {
            authorizeOutput(call, config.token, apiToken)
            playlist(call, runtime, tokenQuery(config.token))
        }
        get("/guide.xml") {
            authorizeOutput(call, config.token, apiToken)
            val days = call.request.queryParameters["days"]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..MAX_GUIDE_DAYS }
                    ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_QUERY", "days must be between 1 and 14")
            } ?: runtime.settings.current.guideDays
            guide(call, runtime, clock.instant(), days)
        }
        get("/stream/{id}") {
            authorizeOutput(call, config.token, apiToken)
            val id = call.parameters["id"]!!.substringBefore('.')
            val entry =
                runtime.lineup.entry(id)?.takeIf { it.enabled }
                    ?: throw ApiFailure(HttpStatusCode.NotFound, "CHANNEL_NOT_FOUND", "Unknown channel")
            val base = publicBase(call)
            serveExternal(call, runtime, entry.id, shareHls = true) { session ->
                call.respondRedirect(base + session.descriptor.url)
                true
            }
        }
    }
}

private const val MAX_GUIDE_DAYS = 14

/** How far back the guide starts, so a player tuning in mid-program still sees it. */
private val GUIDE_LOOKBACK = Duration.ofHours(3)

/** The M3U playlist of the lineup's channels, each stream URL carrying [query]. */
private suspend fun playlist(call: ApplicationCall, runtime: LiveRuntime, query: String) {
    val base = publicBase(call)
    val channels = runtime.catalog.catalog().channels
    val entries = runtime.lineup.entries().associateBy { it.id }
    val preferred = runtime.sessions.sources.preferredOfferings(channels.map { it.id })
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondTextWriter(ContentType.parse("audio/x-mpegurl").withCharset(Charsets.UTF_8)) {
        write("#EXTM3U url-tvg=\"$base/output/guide.xml$query\" x-tvg-url=\"$base/output/guide.xml$query\"\n")
        for (channel in channels) {
            // Players infer the stream type from the suffix; a channel with no eligible source
            // lists as TS and fails at play time.
            val suffix = if (preferred[channel.id]?.protocol == "hls") "m3u8" else "ts"
            write(
                "#EXTINF:-1 tvg-id=\"${channel.id}\" tvg-chno=\"${attribute(channel.displayNumber)}\" " +
                    "tvg-name=\"${attribute(channel.name)}\"",
            )
            channel.logo?.let { write(" tvg-logo=\"${runtime.logos.publicUrl(base, it)}\"") }
            entries[channel.id]?.group?.let { write(" group-title=\"${attribute(it)}\"") }
            write(",${attribute(channel.name)}\n$base/output/stream/${channel.id}.$suffix$query\n")
        }
    }
}

/** The XMLTV guide of the lineup's channels for [days] from [now]. */
private suspend fun guide(call: ApplicationCall, runtime: LiveRuntime, now: Instant, days: Int) {
    val from = now.minus(GUIDE_LOOKBACK)
    val to = now.plus(Duration.ofDays(days.toLong()))
    val channels = runtime.catalog.catalog().channels
    val entries = channels.mapNotNull { channel -> runtime.lineup.entry(channel.id) }.associateBy { it.id }
    val mappings = channels.mapNotNull { channel -> entries[channel.id]?.guide?.let { channel.id to it } }
    val programs = HashMap<String, List<GuideProgram>>()
    for ((guideId, group) in mappings.groupBy { it.second.guideId }) {
        val rows = runtime.guides.programs(guideId, group.map { it.second.channelId }, from, to)
        for ((entryId, mapping) in group) programs[entryId] = rows[mapping.channelId].orEmpty()
    }
    val base = publicBase(call)
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondTextWriter(ContentType.Application.Xml.withCharset(Charsets.UTF_8)) {
        write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"sofa-king-backend\">\n")
        for (channel in channels) {
            write(
                "<channel id=\"${xml(channel.id)}\"><display-name>${xml(channel.name)}</" +
                    "display-name><display-name>${xml(channel.displayNumber)}</display-name>",
            )
            channel.logo?.let { write("<icon src=\"${xml(runtime.logos.publicUrl(base, it))}\"/>") }
            write("</channel>\n")
        }
        for (channel in channels) {
            val group = entries[channel.id]?.group
            // A channel with nothing in the window gets hourly placeholders, so Plex
            // lists and records it like the rest.
            val listed = programs[channel.id].orEmpty().ifEmpty {
                placeholders(channel.id, channel.name, from, to)
            }
            for (program in listed) write(xmltvProgram(channel.id, program, group))
        }
        write("</tv>\n")
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
    val joined = if (shareHls) sessions.external.joinable(channelId) else null
    if (joined != null) {
        hls(joined)
    } else {
        val opened = sessions.external.open(channelId, call.request.origin.remoteAddress)
        var release = true
        try {
            release = serveOpened(call, runtime, opened, hls)
        } finally {
            if (release) withContext(NonCancellable) { sessions.release(opened.id) }
        }
    }
}

/** Serves [session], once more if the first fetch found the stream changed; true when it is to be released. */
private suspend fun serveOpened(
    call: ApplicationCall,
    runtime: LiveRuntime,
    session: PlaybackSession,
    hls: suspend (PlaybackSession) -> Boolean,
    retried: Boolean = false,
): Boolean {
    if (session.descriptor.protocol == "hls") return !hls(session)
    try {
        runtime.relay.serve(call, session.id, session.descriptor.epoch, session.descriptor.url.substringAfterLast('/'))
    } catch (e: ApiFailure) {
        if (e.code != "STREAM_CHANGED" || retried) throw e
        return serveOpened(call, runtime, runtime.sessions.get(session.id), hls, retried = true)
    }
    return true
}

/**
 * The output token in the query or as a bearer, or the API token as a bearer. With neither configured the outputs are
 * open to this machine only, as the API is, so a reverse proxy cannot expose them.
 */
internal fun authorizeOutput(call: ApplicationCall, outputToken: String?, apiToken: String?) {
    if (outputToken == null && apiToken == null) {
        if (call.request.fromThisMachine()) return
        throw ApiFailure(
            HttpStatusCode.Forbidden,
            "LOOPBACK_ONLY",
            "Without a token the outputs answer only this machine",
        )
    }
    val bearer = call.request.headers[HttpHeaders.Authorization]?.takeIf {
        it.startsWith("Bearer ")
    }?.removePrefix("Bearer ")
    val query = call.request.queryParameters["token"]
    val authorized =
        (outputToken != null && (sameSecret(query, outputToken) || sameSecret(bearer, outputToken))) ||
            (apiToken != null && sameSecret(bearer, apiToken))
    if (!authorized) {
        call.authFailed()
        throw ApiFailure(HttpStatusCode.Unauthorized, "AUTH_REQUIRED", "Output token required")
    }
}

private val HOST_HEADER = Regex("[A-Za-z0-9.\\-\\[\\]:]{1,253}")

/**
 * The address the player reached us by, so generated URLs work through port mappings;
 * `X-Forwarded-Proto` names a TLS proxy.
 */
internal fun publicBase(call: ApplicationCall): String {
    val host = call.request.headers[HttpHeaders.Host]?.takeIf { it.matches(HOST_HEADER) }
        ?: "${call.request.origin.localAddress}:${call.request.origin.localPort}"
    val scheme = call.request.headers["X-Forwarded-Proto"]?.takeIf { it == "https" } ?: call.request.origin.scheme
    return "$scheme://$host"
}

internal fun tokenQuery(token: String?): String = token?.let { "?token=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""

private val LINE_BREAK = Regex("[\\r\\n]")

private fun attribute(value: String): String = value.replace('"', '\'').replace(LINE_BREAK, " ").trim()
