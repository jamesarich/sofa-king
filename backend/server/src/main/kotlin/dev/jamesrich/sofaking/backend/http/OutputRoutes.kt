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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
        get("/guide.xml") {
            authorizeOutput(call, config.token, apiToken)
            val days = call.request.queryParameters["days"]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..14 }
                    ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_QUERY", "days must be between 1 and 14")
            } ?: runtime.settings.current.guideDays
            val now = clock.instant()
            val from = now.minus(Duration.ofHours(3))
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
    if (shareHls) {
        sessions.external.joinable(channelId)?.let {
            hls(it)
            return
        }
    }
    val opened = sessions.external.open(channelId, call.request.origin.remoteAddress)
    var release = true
    try {
        var current = opened
        for (attempt in 0..1) {
            if (current.descriptor.protocol == "hls") {
                release = !hls(current)
                return
            }
            try {
                runtime.relay.serve(
                    call,
                    current.id,
                    current.descriptor.epoch,
                    current.descriptor.url.substringAfterLast('/'),
                )
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

private val xmltvFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

private fun xmltvTime(instant: Instant): String = xmltvFormat.format(instant) + " +0000"

private val airDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

private fun airDate(instant: Instant): String = airDateFormat.format(instant)

/** One program titled [name] for each hour from the hour [from] falls in up to [to]. */
internal fun placeholders(channelId: String, name: String, from: Instant, to: Instant): List<GuideProgram> =
    generateSequence(from.truncatedTo(java.time.temporal.ChronoUnit.HOURS)) { it.plus(Duration.ofHours(1)) }
        .takeWhile { it < to }
        .map { start -> GuideProgram(channelId, start, start.plus(Duration.ofHours(1)), name) }
        .toList()

internal const val XMLTV_MOVIE = "Movie"

/** The kinds Plex sorts a program by, in the order a program's categories list them. */
private val PLEX_KINDS = listOf(XMLTV_MOVIE, "Sports", "News", "Children", "Series").map(String::lowercase)

/**
 * One `<programme>` line for the channel [channelId], children in XMLTV DTD order. The categories are the source's
 * own with any Plex kind first, else the ones [xmltvCategories] derives from the channel's [group]. A program the
 * source did not number and that is not a movie gets its start time as `original-air-date`, since Plex files a
 * program without episode numbering as a movie.
 */
internal fun xmltvProgram(channelId: String, program: GuideProgram, group: String?): String = buildString {
    val lang = program.language?.let { " lang=\"${xml(it)}\"" }.orEmpty()
    append(
        "<programme start=\"${xmltvTime(program.startsAt)}\" stop=\"${xmltvTime(program.endsAt)}\" " +
            "channel=\"${xml(channelId)}\">",
    )
    append("<title$lang>${xml(program.title)}</title>")
    program.subTitle?.let { append("<sub-title$lang>${xml(it)}</sub-title>") }
    program.description?.let { append("<desc$lang>${xml(it)}</desc>") }
    program.date?.let { append("<date>${xml(it)}</date>") }
    val derived = xmltvCategories(group)
    if (program.categories.isEmpty()) {
        derived.forEach { append("<category lang=\"en\">${xml(it)}</category>") }
    } else {
        program.categories.sortedBy { category ->
            PLEX_KINDS.indexOf(category.lowercase()).let {
                if (it < 0) {
                    PLEX_KINDS.size
                } else {
                    it
                }
            }
        }
            .forEach { append("<category$lang>${xml(it)}</category>") }
    }
    program.icon?.let { append("<icon src=\"${xml(it)}\"/>") }
    program.episodeNumbers.forEach {
        append("<episode-num system=\"${xml(it.system)}\">${xml(it.value)}</episode-num>")
    }
    val movie = derived.first() == XMLTV_MOVIE || program.categories.any { it.equals(XMLTV_MOVIE, ignoreCase = true) }
    if (program.episodeNumbers.isEmpty() && !movie) {
        append("<episode-num system=\"original-air-date\">${airDate(program.startsAt)}</episode-num>")
    }
    program.previouslyShown?.let { shown ->
        append(shown.start?.let { "<previously-shown start=\"${xml(it)}\"/>" } ?: "<previously-shown/>")
    }
    if (program.isNew) append("<new/>")
    program.rating?.let { rating ->
        append(rating.system?.let { "<rating system=\"${xml(it)}\">" } ?: "<rating>")
        append("<value>${xml(rating.value)}</value></rating>")
    }
    append("</programme>\n")
}

/**
 * A station group's XMLTV categories: the kind Plex sorts by (Movie, Sports, News, Children or Series), then the group.
 */
internal fun xmltvCategories(group: String?): List<String> {
    val kind = when (group?.lowercase()) {
        "movies" -> XMLTV_MOVIE
        "sports" -> "Sports"
        "news", "local", "local news (us)" -> "News"
        "kids & family" -> "Children"
        else -> "Series"
    }
    return listOfNotNull(kind, group?.takeIf { it.isNotBlank() && !it.equals(kind, ignoreCase = true) })
}

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
