package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.http.info
import dev.jamesrich.sofaking.backend.http.serverStatus
import dev.jamesrich.sofaking.backend.jobs.JobStatus
import dev.jamesrich.sofaking.backend.update.UpdateState
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.Program
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

// The tools that change nothing, each over the service the matching API route or portal page reads.

internal fun Server.readTools(context: McpContext) {
    serverTools(context)
    logTool(context)
    sourceTools(context)
    guideTools(context)
    context.runtime.libraries?.let { libraries ->
        tool(
            "libraries",
            "Every media library with its folder, item counts by kind and how its last scan went.",
            annotations = READ_ONLY,
        ) {
            val counts = libraries.counts()
            libraries.libraries().map { it.info(counts[it.id].orEmpty()) }
        }
        tool(
            "search_library",
            "Movies, shows, videos and music whose title holds the query, then those with a matching cast member " +
                "or director, then episodes; at most 50.",
            schema(QUERY to string("Words to find, 1 to $QUERY_LIMIT characters"), required = listOf(QUERY)),
            READ_ONLY,
        ) { arguments ->
            val query = arguments.string(QUERY)?.takeIf { it.length <= QUERY_LIMIT }
                ?: invalid("query is 1 to $QUERY_LIMIT characters")
            libraries.catalog.search(query, SEARCH_RESULTS, music = true)
        }
        tool(
            "library_home",
            "What the apps' library home shows: Continue watching, the Watchlist, and Recently added per library.",
            annotations = READ_ONLY,
        ) { libraries.catalog.home(music = true) }
    }
    context.runtime.dvr?.let { dvr ->
        tool(
            "recordings",
            "Every recording with its state (scheduled, recording, completed, failed or canceled) and any " +
                "conflict, scheduled and running soonest first, and the recordings folder's free space.",
            annotations = READ_ONLY,
        ) { dvr.recordings() }
    }
}

private fun Server.serverTools(context: McpContext) {
    val runtime = context.runtime
    tool(
        "server_status",
        "The server's health at a glance: build, uptime, channels, source health counts, provider and guide " +
            "imports with any failing, the lineup policy's last run, Plex sync and how many streams play.",
        annotations = READ_ONLY,
    ) { serverStatus(runtime, context.clock, context.revision) }
    context.attention?.let { attention ->
        tool(
            "needs_attention",
            "What the admin portal's home page lists as needing the operator, worst first, each with the portal " +
                "page that fixes it and the doc that explains it. Empty when all is well.",
            annotations = READ_ONLY,
        ) { attention() }
    }
    tool(
        "update_status",
        "What build runs, what the registry publishes, whether the server is up to date, and what the host's " +
            "updater last did.",
        annotations = READ_ONLY,
    ) {
        runtime.updates?.let { updateInfo(it.state()) }
            ?: UpdateInfo(checking = false, runningRevision = context.revision)
    }
    tool(
        "jobs",
        "The background jobs (imports, guide refreshes, policy runs, Plex sync, library scans, backups, update " +
            "checks): when each last ran, how it went and when it runs next. run_job takes the ids.",
        annotations = READ_ONLY,
    ) { runtime.jobs.snapshot().map { it.info() } }
    tool(
        "watching_now",
        "The live streams playing now: channel, player address, source, bitrate, stalls and source switches.",
        annotations = READ_ONLY,
    ) { runtime.sessions.snapshot() }
}

private fun Server.logTool(context: McpContext) {
    tool(
        "recent_log",
        "The server's newest log lines since it started, newest first, as the portal's Events page lists them.",
        schema(
            "level" to choice("The lowest level to list; default warn", listOf("info", "warn", "error")),
            QUERY to string("Only lines whose message or logger holds this"),
            LIMIT to integer("How many lines", 1..LOG_LIMIT, DEFAULT_LOG_LINES),
        ),
        READ_ONLY,
    ) { arguments ->
        val levels = when (arguments.string("level") ?: "warn") {
            "info" -> null
            "warn" -> setOf("WARN", "ERROR")
            "error" -> setOf("ERROR")
            else -> invalid("level is info, warn or error")
        }
        val query = arguments.string(QUERY)
        context.log.snapshot().asSequence()
            .filter { levels == null || it.level in levels }
            .filter {
                query == null || it.message.contains(query, ignoreCase = true) ||
                    it.logger.contains(query, ignoreCase = true)
            }
            .take(arguments.int(LIMIT, DEFAULT_LOG_LINES, 1..LOG_LIMIT))
            .map { LogLine(it.at.toString(), it.level, it.logger, it.message, it.requestId) }
            .toList()
    }
}

private fun Server.sourceTools(context: McpContext) {
    val runtime = context.runtime
    tool(
        "imports",
        "Each provider's and guide's last import: when it last succeeded, its error code, and what it brought in.",
        annotations = READ_ONLY,
    ) { Imports(runtime.feeds.statuses, runtime.guides.statuses) }
    tool(
        "source_problems",
        "The stream sources that are not healthy (unverified, suspect, probation, quarantined, archived or " +
            "withdrawn) or are disabled by hand, with counts by state over every source.",
        schema(
            "status" to string("Only sources in this state"),
            LIMIT to integer("How many sources to list", 1..SOURCE_LIMIT, DEFAULT_SOURCES),
        ),
        READ_ONLY,
    ) { arguments ->
        val status = arguments.string("status")
        val limit = arguments.int(LIMIT, DEFAULT_SOURCES, 1..SOURCE_LIMIT)
        val all = runtime.feeds.sources()
        val problems = all.filter {
            if (status != null) it.status == status else it.status != HEALTHY || it.manuallyDisabled
        }
        SourceProblems(
            total = all.size,
            count = all.groupingBy { it.status }.eachCount().toSortedMap(),
            manuallyDisabled = all.count { it.manuallyDisabled },
            sources = problems.take(limit),
            truncated = problems.size > limit,
        )
    }
}

private fun Server.guideTools(context: McpContext) {
    val runtime = context.runtime
    tool(
        "guide_now",
        "What is on now and next on the enabled channels, in lineup order.",
        schema(
            "channel" to string("Only channels with this number, or whose name holds this"),
            LIMIT to integer("How many channels", 1..GUIDE_CHANNELS, DEFAULT_GUIDE_CHANNELS),
        ),
        READ_ONLY,
    ) { arguments ->
        val filter = arguments.string("channel")
        val channels = runtime.catalog.catalog().channels
            .filter { filter == null || it.displayNumber == filter || it.name.contains(filter, ignoreCase = true) }
            .take(arguments.int(LIMIT, DEFAULT_GUIDE_CHANNELS, 1..GUIDE_CHANNELS))
        nowNext(context, channels)
    }
    tool(
        "on_later",
        "Movies, sports, kids' programs or premieres coming up on the enabled channels, soonest first.",
        schema(
            "kind" to choice("What to list", listOf("movie", "sports", "kids", "new")),
            "days" to integer("How many days ahead", 1..ON_LATER_DAYS, DEFAULT_ON_LATER_DAYS),
            LIMIT to integer("How many programs", 1..ON_LATER_LIMIT, DEFAULT_ON_LATER),
            required = listOf("kind"),
        ),
        READ_ONLY,
    ) { arguments ->
        val upcoming = runtime.onLater.programs(
            arguments.string("kind").orEmpty(),
            arguments.int("days", DEFAULT_ON_LATER_DAYS, 1..ON_LATER_DAYS),
            arguments.int(LIMIT, DEFAULT_ON_LATER, 1..ON_LATER_LIMIT),
        )
        val names = runtime.catalog.catalog().channels.associate { it.id to "${it.displayNumber} ${it.name}" }
        upcoming.programs.map { it.airing(names[it.channelId]) }
    }
}

private suspend fun nowNext(context: McpContext, channels: List<Channel>): List<NowNext> {
    if (channels.isEmpty()) return emptyList()
    val now = context.clock.instant().truncatedTo(ChronoUnit.SECONDS)
    val window = context.runtime.catalog.guide(
        GuideQuery(channels.map { it.id }, now.toString(), now.plus(NOW_NEXT_WINDOW).toString()),
    )
    val programs = window.channels.associate { it.channelId to it.programs.sortedBy { p -> Instant.parse(p.startsAt) } }
    return channels.map { channel ->
        val rows = programs[channel.id].orEmpty()
        val current = rows.firstOrNull { Instant.parse(it.startsAt) <= now && Instant.parse(it.endsAt) > now }
        val after = current?.let { Instant.parse(it.endsAt) } ?: now
        val next = rows.firstOrNull { Instant.parse(it.startsAt) >= after }
        NowNext(channel.id, channel.displayNumber, channel.name, current?.airing(), next?.airing())
    }
}

internal fun updateInfo(state: UpdateState) = UpdateInfo(
    checking = state.image != null,
    runningRevision = state.running.revision,
    runningVersion = state.running.version,
    image = state.image,
    publishedRevision = state.published?.revision,
    publishedVersion = state.published?.version,
    upToDate = state.current,
    checkedAt = state.checkedAt?.toString(),
    error = state.error,
    updater = state.updater,
    requestedAt = state.requestedAt?.toString(),
)

private fun Program.airing(channel: String? = null) =
    Airing(title, startsAt, endsAt, subTitle, episode, isNew, categories, channel)

private fun JobStatus.info() = JobInfo(
    id = id,
    label = label,
    everyMinutes = every?.toMinutes(),
    running = running,
    progress = progress?.let { "${it.done} of ${it.total}" + (it.step?.let { step -> ", $step" } ?: "") },
    lastStartedAt = lastStartedAt?.toString(),
    lastDurationMs = lastDurationMs,
    lastOk = lastOk,
    lastNote = lastNote,
    nextDueAt = nextDueAt?.toString(),
    canRunNow = canRunNow,
)

private const val QUERY = "query"
private const val LIMIT = "limit"
private const val QUERY_LIMIT = 100
private const val SEARCH_RESULTS = 50
private const val HEALTHY = "healthy"
private const val LOG_LIMIT = 500
private const val DEFAULT_LOG_LINES = 50
private const val SOURCE_LIMIT = 1000
private const val DEFAULT_SOURCES = 100
private const val GUIDE_CHANNELS = 200
private const val DEFAULT_GUIDE_CHANNELS = 50
private const val ON_LATER_DAYS = 7
private const val DEFAULT_ON_LATER_DAYS = 2
private const val ON_LATER_LIMIT = 500
private const val DEFAULT_ON_LATER = 50

/** How far ahead `guide_now` looks for the next program. */
private val NOW_NEXT_WINDOW = Duration.ofHours(6)
