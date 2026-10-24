package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.GuideService
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.library.PlayableFile
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.Recordings
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.deleteRecording
import dev.jamesrich.sofaking.backend.store.guides
import dev.jamesrich.sofaking.backend.store.lineup
import dev.jamesrich.sofaking.backend.store.programs
import dev.jamesrich.sofaking.backend.store.recording
import dev.jamesrich.sofaking.backend.store.recordingRules
import dev.jamesrich.sofaking.backend.store.recordings
import dev.jamesrich.sofaking.backend.store.saveRecording
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingList
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.UpdateRecording
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The DVR: series rules and one-off recordings, the schedule the rules make from the guide, and the recordings
 * themselves. A scheduled recording starts its [Recorder] when its padded window opens and is written under the
 * recordings folder's `.inprogress` folder, then moved to its [RecordingLayout] path when the window closes.
 */
class Dvr(
    private val store: BackendStore,
    private val lineup: LineupService,
    private val guides: GuideService,
    private val recorder: Recorder,
    /** The recordings folder, `SOFA_RECORDINGS_DIR`. */
    val root: Path,
    private val clock: Clock,
    /** How many recordings may run at once: the playback slots, `SOFA_MAX_SESSIONS`. */
    private val capacity: () -> Int,
    /** How far ahead the scheduler reads the guide. */
    private val horizon: () -> Duration = { HORIZON },
    /** What the culler keeps: free space and age, `SOFA_RECORDINGS_FREE_GIB` and `SOFA_RECORDINGS_KEEP_DAYS`. */
    private val retention: () -> Retention = { Retention() },
    /** The free space on the recordings folder's disk; a seam for tests. */
    private val freeSpace: (Path) -> Long? = { runCatching { Files.getFileStore(it).usableSpace }.getOrNull() },
    /** Looks for commercial breaks in each finished recording; null when `SOFA_COMSKIP` is `none`. */
    private val commercials: Commercials? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val jobs: Jobs = Jobs(clock),
) {
    private val mutex = Mutex()
    private val trigger = Channel<String>(Channel.CONFLATED)
    private lateinit var scope: CoroutineScope

    /** The recordings folder on disk. */
    val files = RecordingFiles(root, freeSpace)

    /** The series rules; a change reschedules. */
    val rules = DvrRules(store, lineup, clock) { requestSchedule("rule") }
    private val scanner = commercials?.let { CommercialScanner(store, files, it, mutex, clock) }
    private val runner = RecordingRunner(store, recorder, files, clock, zone, mutex, scanner?.let { it::request }) {
        if (::scope.isInitialized) scope.launch { cull("finished") }
    }

    init {
        jobs.register(SCHEDULE_JOB, "Recording schedule", SCHEDULE_EVERY) { requestSchedule("now") }
        jobs.register(CULL_JOB, "Recording cleanup", SCHEDULE_EVERY) {
            if (::scope.isInitialized) scope.launch { cull("now") }
        }
    }

    /**
     * Runs the scheduler on guide imports, rule changes and every [SCHEDULE_EVERY], and starts
     * and ends recordings every [TICK].
     */
    fun start(scope: CoroutineScope): List<Job> {
        this.scope = scope
        return listOfNotNull(
            scope.launch(CoroutineName("dvr-schedule")) {
                while (isActive) {
                    val cause =
                        select<String> {
                            trigger.onReceive { it }
                            onTimeout(SCHEDULE_EVERY.toMillis()) { "interval" }
                        }
                    runCatchingCancellable {
                        refreshSchedule(cause)
                    }.onFailure { e ->
                        log.warn("recording schedule failed class={}", e.javaClass.simpleName)
                    }
                    runCatchingCancellable {
                        cull(cause)
                    }.onFailure { e ->
                        log.warn("recording cleanup failed class={}", e.javaClass.simpleName)
                    }
                }
            },
            scanner?.let { scope.launch(CoroutineName("dvr-commercials")) { it.run() } },
            scope.launch(CoroutineName("dvr-tick")) {
                while (isActive) {
                    runCatchingCancellable {
                        tick()
                    }.onFailure { e ->
                        log.warn("recording tick failed class={}", e.javaClass.simpleName)
                    }
                    delay(TICK.toMillis())
                }
            },
        )
    }

    /** Asks the scheduler to run soon; requests before it runs coalesce. */
    fun requestSchedule(cause: String) {
        trigger.trySend(cause)
    }

    // Recordings

    /**
     * Every recording but canceled ones: scheduled and running soonest first, then the rest
     * newest first, with conflicts marked.
     */
    suspend fun recordings(): RecordingList {
        val all = store.recordings().filter { it.state != CANCELED }
        val conflicts = DvrPlanner.conflicts(all, store.recordingRules().associate { it.id to it.priority }, capacity())
        val upcoming = all.filter {
            it.state == Wire.RecordingState.SCHEDULED || it.state == Wire.RecordingState.RECORDING
        }.sortedBy { it.recordFrom }
        val past = (all - upcoming.toSet()).sortedByDescending { it.startsAt }
        return RecordingList((upcoming + past).map { it.toWire(it.id in conflicts) }, files.storage(all))
    }

    suspend fun recording(id: String): Recording = (
        store.recording(id)?.takeIf { it.state != CANCELED }
            ?: throw notFound()
        ).toWire()

    /** The file of a running or finished recording, for playback; null when [id] has recorded nothing. */
    suspend fun playable(id: String): PlayableFile? {
        val recording = store.recording(id) ?: return null
        val relative =
            recording.path?.takeIf {
                recording.state == Wire.RecordingState.RECORDING || recording.state == Wire.RecordingState.COMPLETED
            }
                ?: return null
        val growing = recording.state == Wire.RecordingState.RECORDING
        return PlayableFile(
            files.resolve(relative),
            if (growing) Duration.between(recording.recordFrom, recording.recordUntil).toMillis() else null,
            growing,
        )
    }

    /**
     * Schedules the program on [ScheduleRecording.channelId] that starts at [ScheduleRecording.startsAt], or, with
     * [ScheduleRecording.endsAt], that window of the channel under [ScheduleRecording.title], guide or not.
     */
    suspend fun schedule(request: ScheduleRecording): Recording {
        val entry =
            lineup.entry(request.channelId)?.takeIf { it.enabled }
                ?: throw ApiFailure(HttpStatusCode.NotFound, Wire.ErrorCode.CHANNEL_NOT_FOUND, "Unknown channel")
        val start = instant(request.startsAt, "startsAt")
        val manual = request.endsAt != null
        val program = if (manual) window(entry, request, start) else guides.programAt(entry, start)
        if (program.endsAt <= clock.instant()) {
            throw ApiFailure(HttpStatusCode.Conflict, "PROGRAM_ENDED", "The program has already ended")
        }
        // A window someone picked by the clock is recorded as given; a guide program gets the usual padding.
        val padding = if (manual) Duration.ZERO to Duration.ZERO else DEFAULT_START_PADDING to DEFAULT_END_PADDING
        return mutex.withLock {
            store.recordings().firstOrNull { it.channelId == entry.id && it.startsAt == start }?.let { existing ->
                if (existing.state != CANCELED) return@withLock existing.toWire()
                store.deleteRecording(existing.id)
            }
            val airing = Airing(entry.id, entry.name, program)
            val recording = airing.scheduled(null, padding.first, padding.second, clock.instant())
            store.addRecording(recording)
            recording.toWire()
        }.also { scope.launch { tick() } }
    }

    suspend fun update(id: String, update: UpdateRecording): Recording = mutex.withLock {
        val recording = store.recording(id)?.takeIf { it.state != CANCELED } ?: throw notFound()
        val changed = recording.copy(
            protected = update.protected ?: recording.protected,
            positionMs = update.positionMs?.coerceAtLeast(0) ?: recording.positionMs,
            watched = update.watched ?: recording.watched,
            updatedAt = clock.instant(),
        )
        store.saveRecording(changed)
        changed.toWire()
    }

    /**
     * Cancels a scheduled recording, stops a running one (keeping what it recorded), or deletes a finished one and its
     * files. A canceled rule airing is remembered so the rule does not schedule it again.
     */
    suspend fun delete(id: String) {
        // Decided under the lock, so a recording the tick starts meanwhile is stopped, not overwritten.
        val stopping = mutex.withLock {
            val recording = store.recording(id)?.takeIf { it.state != CANCELED } ?: throw notFound()
            when (recording.state) {
                Wire.RecordingState.RECORDING -> recording

                Wire.RecordingState.SCHEDULED -> {
                    if (recording.ruleId != null) {
                        store.saveRecording(recording.copy(state = CANCELED, updatedAt = clock.instant()))
                    } else {
                        store.deleteRecording(id)
                    }
                    null
                }

                else -> {
                    recording.path?.let { files.remove(it) }
                    store.deleteRecording(id)
                    log.info("recording deleted id={} title=\"{}\"", id, recording.title)
                    null
                }
            }
        }
        stopping?.let { runner.stop(it) }
    }

    // Scheduling

    /** One scheduler pass: adds what the rules match in the guide and drops rule airings the guide no longer has. */
    suspend fun refreshSchedule(cause: String) = jobs.record(SCHEDULE_JOB) {
        val now = clock.instant()
        val rules = store.recordingRules()
        if (rules.isEmpty()) return@record JobOutcome(true, "no rules")
        val airings = ruleAirings(lineup, guides, rules, now, now.plus(horizon()))
        mutex.withLock {
            val plan = DvrPlanner.plan(rules, airings, store.recordings(), now)
            plan.drop.forEach { store.deleteRecording(it.id) }
            val added = plan.add.count { (rule, airing) ->
                store.addRecording(airing.scheduled(rule.id, rule.startPadding, rule.endPadding, clock.instant()))
            }
            log.info(
                "recording schedule cause={} rules={} added={} dropped={}",
                cause,
                rules.size,
                added,
                plan.drop.size,
            )
            JobOutcome(true, "$added added, ${plan.drop.size} dropped")
        }
    }

    // Recording

    /**
     * Starts the recordings whose window has opened, fails the ones whose window passed unrecorded, and
     * resumes any a restart left running.
     */
    suspend fun tick() {
        val now = clock.instant()
        mutex.withLock {
            val all = store.recordings()
            for (missed in all.filter { it.state == Wire.RecordingState.SCHEDULED && it.recordUntil <= now }) {
                store.saveRecording(missed.copy(state = Wire.RecordingState.FAILED, reason = "MISSED", updatedAt = now))
                log.warn("recording missed id={} title=\"{}\"", missed.id, missed.title)
            }
            val due = all.filter {
                (it.state == Wire.RecordingState.SCHEDULED || it.state == Wire.RecordingState.RECORDING) &&
                    it.recordFrom <= now && now < it.recordUntil && !runner.isRunning(it.id)
            }
            // Earliest first, so when slots run short the recording whose window opened first gets one.
            for (recording in due.sortedBy { it.recordFrom }) runner.launch(recording, scope)
        }
        for (orphan in store.recordings().filter {
            it.state == Wire.RecordingState.RECORDING && it.recordUntil <= now && !runner.isRunning(it.id)
        }) {
            runner.finish(orphan, null)
        }
        runner.guardSpace(now, retention().freeGib.toLong() * GIB) { cull("space") }
    }

    // Cleanup

    /**
     * Deletes the finished recordings the retention no longer keeps: a rule's oldest beyond its keep count, any older
     * than the keep days, then, while the disk has less free space than the floor, the oldest of the rest, watched
     * ones first. Protected recordings and running ones are never deleted. Logs every deletion with its reason.
     */
    suspend fun cull(cause: String) = jobs.record(CULL_JOB) {
        val now = clock.instant()
        val keep = retention()
        val deleted = mutex.withLock {
            val rules = store.recordingRules().associateBy { it.id }
            val all = store.recordings()
            val plan = DvrPlanner.cull(all, rules, keep, now)
            plan.forEach { (recording, reason) -> store.cull(files, recording, reason) }
            val gone = plan.mapTo(HashSet()) { it.first.id }
            val floor = keep.freeGib.toLong() * GIB
            val oldest = DvrPlanner.byCullOrder(all.filter { it.id !in gone }).iterator()
            // The oldest go one at a time until the disk has the floor free, or its free space cannot be read.
            while (oldest.hasNext() && files.free()?.let { it < floor } == true) {
                val recording = oldest.next()
                store.cull(files, recording, "SPACE")
                gone += recording.id
            }
            gone.size
        }
        JobOutcome(true, "$deleted deleted").also {
            if (deleted > 0) {
                log.info("recording cleanup cause={} deleted={}", cause, deleted)
            }
        }
    }

    companion object {
        const val SCHEDULE_JOB = "dvr-schedule"
        const val DISK_FULL = "DISK_FULL"
        const val CULL_JOB = "dvr-cull"
        internal const val GIB = 1024L * 1024 * 1024
        val DEFAULT_START_PADDING: Duration = Duration.ofMinutes(1)
        val DEFAULT_END_PADDING: Duration = Duration.ofMinutes(2)
        const val MAX_PADDING_SECONDS = 3600

        /** How far ahead the scheduler reads the guide unless a test says otherwise. */
        private val HORIZON: Duration = Duration.ofDays(3)

        /** The longest window a manual recording may span. */
        val MAX_MANUAL: Duration = Duration.ofHours(12)

        /**
         * Below this much free space running recordings stop, whatever the cleanup floor,
         * so the database can still write.
         */
        const val MIN_FREE_BYTES = 512L * 1024 * 1024

        /** How often a running recording may make cleanup run for space. */
        val SPACE_CULL_EVERY: Duration = Duration.ofMinutes(1)
        val SCHEDULE_EVERY: Duration = Duration.ofMinutes(30)
        val TICK: Duration = Duration.ofSeconds(5)
    }
}

private val log = LoggerFactory.getLogger(Dvr::class.java)

/** A recording of this airing for [ruleId], or a one-off without one, padded as given and scheduled at [now]. */
private fun Airing.scheduled(
    ruleId: String?,
    startPadding: Duration,
    endPadding: Duration,
    now: Instant,
): StoredRecording {
    val name = RecordingTitle.of(program)
    return StoredRecording(
        UUID.randomUUID().toString(), ruleId, channelId, channelName, program.startsAt,
        program.endsAt, startPadding, endPadding,
        program.title, name.episodeTitle, program.description, name.numbering?.season, name.numbering?.episode,
        name.movie, name.year, program.icon,
        Wire.RecordingState.SCHEDULED, createdAt = now, updatedAt = now,
    )
}

/** The guide's airings in [from, to) on the channels the rules name, or on every enabled channel with a guide. */
private suspend fun ruleAirings(
    lineup: LineupService,
    guides: GuideService,
    rules: List<StoredRule>,
    from: Instant,
    to: Instant,
): List<Airing> {
    val anyChannel = rules.any { it.channelId == null }
    val wanted = rules.mapNotNullTo(HashSet()) { it.channelId }
    val titles = rules.mapTo(HashSet()) { it.title.trim().lowercase() }
    val entries = lineup.entries().filter { it.enabled && it.guide != null && (anyChannel || it.id in wanted) }
    return entries.groupBy { it.guide!!.guideId }.flatMap { (guideId, group) ->
        val rows = guides.programs(guideId, group.map { it.guide!!.channelId }.distinct(), from, to)
        group.flatMap { entry ->
            rows[entry.guide!!.channelId].orEmpty().filter {
                it.title.trim().lowercase() in titles
            }.map { Airing(entry.id, entry.name, it) }
        }
    }
}

/** The time window [request] gives on [entry] from [start], under its title or the channel's name. */
private fun window(entry: LineupEntryRecord, request: ScheduleRecording, start: Instant): GuideProgram {
    val end = instant(request.endsAt!!, "endsAt")
    if (end <= start || Duration.between(start, end) > Dvr.MAX_MANUAL) {
        throw ApiFailure(
            HttpStatusCode.BadRequest,
            "INVALID_REQUEST",
            "endsAt must be after startsAt and at most ${Dvr.MAX_MANUAL.toHours()} hours later",
        )
    }
    val title = request.title?.trim()?.takeIf { it.isNotEmpty() } ?: entry.name
    if (title.length > MANUAL_TITLE_LIMIT) {
        throw ApiFailure(
            HttpStatusCode.BadRequest,
            "INVALID_REQUEST",
            "A title has at most $MANUAL_TITLE_LIMIT characters",
        )
    }
    return GuideProgram(channelId = entry.id, startsAt = start, endsAt = end, title = title)
}

private const val MANUAL_TITLE_LIMIT = 200

/** The guide program that starts at [start] on [entry]. */
private suspend fun GuideService.programAt(entry: LineupEntryRecord, start: Instant): GuideProgram {
    val mapping =
        entry.guide
            ?: throw ApiFailure(
                HttpStatusCode.NotFound,
                "PROGRAM_NOT_FOUND",
                "The channel has no guide; give endsAt to record a time window",
            )
    return programs(mapping.guideId, listOf(mapping.channelId), start, start.plusSeconds(1))[mapping.channelId]
        ?.firstOrNull { it.startsAt == start }
        ?: throw ApiFailure(
            HttpStatusCode.NotFound,
            "PROGRAM_NOT_FOUND",
            "No program starts then on that channel",
        )
}

/** Deletes [recording] and its files for [reason], and logs why. */
private suspend fun BackendStore.cull(files: RecordingFiles, recording: StoredRecording, reason: String) {
    withContext(Dispatchers.IO) { recording.path?.let { files.remove(it) } }
    deleteRecording(recording.id)
    log.info("recording culled id={} title=\"{}\" reason={}", recording.id, recording.title, reason)
}

private fun instant(text: String, field: String): Instant = try {
    Instant.parse(text)
} catch (_: Exception) {
    throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid $field timestamp")
}

private fun notFound() = ApiFailure(HttpStatusCode.NotFound, "RECORDING_NOT_FOUND", "Unknown recording")
