package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.api.NamedOutput
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.sameSecret
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
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The lineup for third-party players under `/output`: an M3U playlist, an XMLTV guide and one stream URL per
 * channel. Players send no headers, so the token travels in the query and the generated URLs carry it. Each named
 * output has its own playlist and guide under `/output/{name}`, holding its channels only.
 */
fun Route.outputRoutes(runtime: LiveRuntime, clock: Clock, config: OutputConfig, apiToken: String?) {
    route("/output") {
        /** The channels [call] asks for, once it is authorized: the named output's, else the whole lineup. */
        suspend fun selection(call: ApplicationCall): Selection {
            authorizeOutput(call, config.token, apiToken)
            val name = call.parameters["name"] ?: return Selection("/output", null)
            val output = runtime.namedOutputs.get(name)
                ?: throw ApiFailure(HttpStatusCode.NotFound, "OUTPUT_NOT_FOUND", "Unknown output")
            return Selection("/output/$name", output)
        }
        for (prefix in listOf("", "/{name}")) {
            get("$prefix/playlist.m3u") {
                val selection = selection(call)
                val allTs = tsOnly(call)
                val query = tokenQuery(config.token).let { if (allTs) withParameter(it, "format=ts") else it }
                playlist(call, runtime, query, allTs, selection)
            }
            get("$prefix/guide.xml") {
                val selection = selection(call)
                val days = call.request.queryParameters["days"]?.let { raw ->
                    raw.toIntOrNull()?.takeIf { it in 1..MAX_GUIDE_DAYS }
                        ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_QUERY", "days must be between 1 and 14")
                } ?: runtime.settings.current.guideDays
                guide(call, runtime, clock.instant(), days, selection)
            }
        }
        get("/stream/{id}") {
            authorizeOutput(call, config.token, apiToken)
            val id = call.parameters["id"]!!.substringBefore('.')
            val entry =
                runtime.lineup.entry(id)?.takeIf { it.enabled }
                    ?: throw ApiFailure(HttpStatusCode.NotFound, "CHANNEL_NOT_FOUND", "Unknown channel")
            val base = publicBase(call)
            if (call.request.queryParameters["format"] == "ts") {
                // A player that takes one stream format for the whole playlist, such as Channels DVR's MPEG-TS
                // source: an HLS source is remuxed in this request, as for an HDHomeRun tuner.
                serveExternal(call, runtime, entry.id, shareHls = false) { session -> remux(call, runtime, session.id) }
            } else {
                serveExternal(call, runtime, entry.id, shareHls = true) { session ->
                    call.respondRedirect(base + session.descriptor.url)
                    true
                }
            }
        }
    }
}

private const val MAX_GUIDE_DAYS = 14

/** The channels one playlist or guide holds: [output]'s, or all of them without one; its URLs sit under [path]. */
private class Selection(val path: String, val output: NamedOutput?) {
    fun includes(entry: LineupEntryRecord?): Boolean = output == null || output.holds(entry)
}

/** Whether the request asks for every stream as MPEG-TS, `format=ts`; any other format answers 400. */
private fun tsOnly(call: ApplicationCall): Boolean {
    val format = call.request.queryParameters["format"] ?: return false
    if (format != "ts") throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_QUERY", "format must be ts")
    return true
}

/** How far back the guide starts, so a player tuning in mid-program still sees it. */
private val GUIDE_LOOKBACK = Duration.ofHours(3)

/**
 * The M3U playlist of the lineup's channels, each stream URL carrying [query]. A channel with a Gracenote id in the
 * policy carries `tvc-guide-stationid`, so Channels DVR takes its guide from Gracenote; any other channel with a logo
 * carries it as `tvc-guide-art`, the artwork Channels shows for its programs. With [allTs] every stream URL serves
 * MPEG-TS. Only the [selection]'s channels are listed.
 */
private suspend fun playlist(
    call: ApplicationCall,
    runtime: LiveRuntime,
    query: String,
    allTs: Boolean,
    selection: Selection,
) {
    val base = publicBase(call)
    val entries = runtime.lineup.entries().associateBy { it.id }
    val channels = runtime.catalog.catalog().channels.filter { selection.includes(entries[it.id]) }
    val preferred = runtime.sessions.sources.preferredOfferings(channels.map { it.id })
    val gracenote = runtime.policy?.lastApplied()?.stations.orEmpty()
        .mapNotNull { station -> station.gracenoteId?.let { station.id to it } }.toMap()
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondTextWriter(ContentType.parse("audio/x-mpegurl").withCharset(Charsets.UTF_8)) {
        val guide = "$base${selection.path}/guide.xml$query"
        write("#EXTM3U url-tvg=\"$guide\" x-tvg-url=\"$guide\"\n")
        for (channel in channels) {
            // Players infer the stream type from the suffix; a channel with no eligible source
            // lists as TS and fails at play time.
            val suffix = if (!allTs && preferred[channel.id]?.protocol == "hls") "m3u8" else "ts"
            write(
                "#EXTINF:-1 tvg-id=\"${channel.id}\" tvg-chno=\"${attribute(channel.displayNumber)}\" " +
                    "tvg-name=\"${attribute(channel.name)}\"",
            )
            val logo = channel.logo?.let { runtime.logos.publicUrl(base, it) }
            logo?.let { write(" tvg-logo=\"$it\"") }
            entries[channel.id]?.group?.let { write(" group-title=\"${attribute(it)}\"") }
            val stationId = gracenote[channel.id]
            if (stationId != null) {
                write(" tvc-guide-stationid=\"$stationId\"")
            } else if (logo != null) {
                write(" tvc-guide-art=\"$logo\"")
            }
            write(",${attribute(channel.name)}\n$base/output/stream/${channel.id}.$suffix$query\n")
        }
    }
}

/** The XMLTV guide of the [selection]'s channels for [days] from [now]. */
private suspend fun guide(call: ApplicationCall, runtime: LiveRuntime, now: Instant, days: Int, selection: Selection) {
    val from = now.minus(GUIDE_LOOKBACK)
    val to = now.plus(Duration.ofDays(days.toLong()))
    val all = runtime.catalog.catalog().channels
    val entries = all.mapNotNull { channel -> runtime.lineup.entry(channel.id) }.associateBy { it.id }
    val channels = all.filter { selection.includes(entries[it.id]) }
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

/** [query], a query string or empty, with [parameter] added. */
private fun withParameter(query: String, parameter: String): String =
    if (query.isEmpty()) "?$parameter" else "$query&$parameter"

private val LINE_BREAK = Regex("[\\r\\n]")

private fun attribute(value: String): String = value.replace('"', '\'').replace(LINE_BREAK, " ").trim()
