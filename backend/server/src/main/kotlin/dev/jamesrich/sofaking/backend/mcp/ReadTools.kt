package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.http.info
import dev.jamesrich.sofaking.backend.http.serverStatus
import dev.jamesrich.sofaking.backend.jobs.JobStatus
import dev.jamesrich.sofaking.backend.update.UpdateState
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.Program
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.Wire
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

// The tools that change nothing, each over the service the matching API route or portal page reads.

internal fun Server.readTools(context: McpContext) {
    serverTools(context)
    logTool(context)
    sourceTools(context)
    lineupTools(context)
    guideTools(context)
    policyTools(context)
    libraryTools(context)
    recordingTools(context)
    diagnosticsTools(context)
}

private fun Server.libraryTools(context: McpContext) {
    val libraries = context.runtime.libraries ?: return
    tool(
        "libraries",
        "Use first for anything about media libraries: every library with its id, folder, item counts by kind and " +
            "how its last scan went. scan_library (admin) takes the ids.",
        annotations = READ_ONLY,
    ) {
        val counts = libraries.counts()
        libraries.libraries().map { it.info(counts[it.id].orEmpty()) }
    }
    tool(
        "search_library",
        "Find a movie, show, video or music title the libraries hold. Returns movies, shows, videos and music " +
            "whose title holds the query, then those with a matching cast member or director, then episodes; at " +
            "most $SEARCH_RESULTS.",
        schema(QUERY to string("Words to find, 1 to $QUERY_LIMIT characters"), required = listOf(QUERY)),
        READ_ONLY,
    ) { arguments ->
        val query = arguments.string(QUERY)?.takeIf { it.length <= QUERY_LIMIT }
            ?: invalid("query is 1 to $QUERY_LIMIT characters")
        libraries.catalog.search(query, SEARCH_RESULTS, music = true)
    }
    tool(
        "library_home",
        "What the apps' library home shows: Continue watching, the Watchlist, and Recently added per library. Each " +
            "shelf lists its first $SHELF_ITEMS items; its total and truncated say whether more exist.",
        annotations = READ_ONLY,
    ) {
        val home = libraries.catalog.home(music = true)
        HomeView(
            home.shelves.map {
                ShelfView(
                    it.id,
                    it.title,
                    it.libraryId,
                    it.items.size,
                    it.items.take(SHELF_ITEMS),
                    it.items.size > SHELF_ITEMS,
                )
            },
            home.libraries,
        )
    }
}

private fun Server.recordingTools(context: McpContext) {
    val dvr = context.runtime.dvr ?: return
    tool(
        "recordings",
        "Use for what is scheduled, recording, done or failed on the DVR. Returns recordings (not canceled ones) " +
            "with state, any conflict and the commercial scan's state and break count, scheduled and running " +
            "soonest first, then the newest kept, and the recordings folder's free space. Filter with state; page " +
            "with offset and limit; channelId is a lineup entry id.",
        schema(
            "state" to choice("Only recordings in this state", RECORDING_STATES),
            LIMIT to integer("How many recordings", 1..MAX_PAGE, DEFAULT_PAGE),
            OFFSET to integer("How many matching recordings to skip", 0..MAX_OFFSET, 0),
        ),
        READ_ONLY,
    ) { arguments ->
        val state = arguments.choice("state", RECORDING_STATES)
        val (offset, limit) = arguments.page()
        val all = dvr.recordings()
        val matching = all.recordings.filter { state == null || it.state == state }
        val rows = matching.drop(offset).take(limit)
        RecordingsPage(
            matching.size,
            offset,
            rows.map { it.row() },
            nextOffset(offset, limit, matching.size),
            truncated = offset + rows.size < matching.size,
            all.storage,
        )
    }
    tool(
        "recording_rules",
        "The series and team rules the DVR records by: the title or terms each matches, its channelId (a lineup " +
            "entry id; absent means any channel), padding, priority and how many it keeps.",
        schema(LIMIT to integer("How many rules", 1..MAX_PAGE, DEFAULT_PAGE)),
        READ_ONLY,
    ) { arguments -> dvr.rules.list().capped(arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)) }
}

private fun Server.serverTools(context: McpContext) {
    val runtime = context.runtime
    tool(
        "server_status",
        "Use first to see how the server is. Returns the build, uptime, channels, source health counts, provider " +
            "and guide imports with any failing, the lineup policy's last run, Plex sync and how many streams play.",
        annotations = READ_ONLY,
    ) { serverStatus(runtime, context.clock, context.revision) }
    context.attention?.let { attention ->
        tool(
            "needs_attention",
            "Use second: what the admin portal's home page lists as needing the operator, worst first, each with " +
                "the portal page that fixes it and the doc that explains it. An empty list means all is well.",
            annotations = READ_ONLY,
        ) { attention() }
    }
    tool(
        "update_status",
        "What build runs, what the registry publishes, whether the server is up to date, and what the host's " +
            "updater last did. Use before update_now.",
        annotations = READ_ONLY,
    ) {
        runtime.updates?.let { updateInfo(it.state()) }
            ?: UpdateInfo(checking = false, runningRevision = context.revision)
    }
    tool(
        "jobs",
        "Use to see why something is stale or to find a job id: the background jobs (imports, guide refreshes, " +
            "policy runs, Plex sync, library scans, backups, update checks) with when each last ran, how it went " +
            "(lastOk, lastNote) and when it runs next. run_job (admin) starts only the ids with canRunNow.",
        annotations = READ_ONLY,
    ) { runtime.jobs.snapshot().map { it.info() } }
    tool(
        "watching_now",
        "Who is watching right now: the live streams playing, with the lineup entry (channelId), player address, " +
            "source (offeringId, a source id source_health takes), bitrate, stalls and source switches. Check " +
            "before update_now, which drops every stream.",
        schema(LIMIT to integer("How many streams", 1..MAX_PAGE, DEFAULT_PAGE)),
        READ_ONLY,
    ) { arguments ->
        val sessions = runtime.sessions.snapshot()
        val limit = arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)
        WatchingNow(sessions.size, sessions.take(limit), sessions.size > limit)
    }
}

private fun Server.logTool(context: McpContext) {
    tool(
        "recent_log",
        "The server's own reason when something fails: its newest log lines since it started, newest first, as " +
            "the portal's Events page lists them. Start with level=error. Lines never carry URLs or tokens.",
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

private fun Server.guideTools(context: McpContext) {
    val runtime = context.runtime
    tool(
        "guide_now",
        "What is on now and next on the enabled channels, in lineup order; each row's channelId is a lineup entry " +
            "id. Filter with channel; page with offset and limit.",
        schema(
            "channel" to string("Only channels with this number, or whose name holds this"),
            LIMIT to integer("How many channels", 1..MAX_PAGE, DEFAULT_PAGE),
            OFFSET to integer("How many matching channels to skip", 0..MAX_OFFSET, 0),
        ),
        READ_ONLY,
    ) { arguments ->
        val filter = arguments.string("channel")
        val (offset, limit) = arguments.page()
        val matching = runtime.catalog.catalog().channels
            .filter { filter == null || it.displayNumber == filter || it.name.contains(filter, ignoreCase = true) }
        GuidePage(
            matching.size,
            offset,
            limit,
            nowNext(context, matching.drop(offset).take(limit)),
            nextOffset(offset, limit, matching.size),
        )
    }
    tool(
        "on_later",
        "What to record or watch soon: movies, sports, kids' programs or premieres coming up on the enabled " +
            "channels, soonest first. Each airing carries its channelId (a lineup entry id) for the recording tools.",
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
        upcoming.programs.map { it.airing(names[it.channelId], it.channelId) }
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

private fun Program.airing(channel: String? = null, channelId: String? = null) =
    Airing(title, startsAt, endsAt, subTitle, episode, isNew, categories, channel, channelId)

internal fun Recording.row() = RecordingRow(
    id, channelId, channelName, title, episodeTitle, season, episode, movie, startsAt, endsAt, state, reason, ruleId,
    bytes, protected, conflict, watched, commercials, breaks.size,
    updatedAt,
)

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

private const val QUERY_LIMIT = 100
private const val SEARCH_RESULTS = 50
private const val SHELF_ITEMS = 20
private const val LOG_LIMIT = 500
private const val DEFAULT_LOG_LINES = 50
private const val ON_LATER_DAYS = 7
private const val DEFAULT_ON_LATER_DAYS = 2
private const val ON_LATER_LIMIT = 500
private const val DEFAULT_ON_LATER = 50

private val RECORDING_STATES = listOf(
    Wire.RecordingState.SCHEDULED,
    Wire.RecordingState.RECORDING,
    Wire.RecordingState.COMPLETED,
    Wire.RecordingState.FAILED,
)

/** How far ahead `guide_now` looks for the next program. */
private val NOW_NEXT_WINDOW = Duration.ofHours(6)
