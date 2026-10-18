package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.GuideService
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.library.PlayableFile
import dev.jamesrich.sofaking.backend.store.*
import dev.jamesrich.sofaking.contract.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

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
    private val horizon: () -> Duration = { Duration.ofDays(3) },
    /** What the culler keeps: free space and age, `SOFA_RECORDINGS_FREE_GIB` and `SOFA_RECORDINGS_KEEP_DAYS`. */
    private val retention: () -> Retention = { Retention() },
    /** The free space on the recordings folder's disk; a seam for tests. */
    private val freeSpace: (Path) -> Long? = { runCatching { Files.getFileStore(it).usableSpace }.getOrNull() },
    /** Looks for commercial breaks in each finished recording; null when `SOFA_COMSKIP` is `none`. */
    private val commercials: Commercials? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val jobs: Jobs = Jobs(clock),
) {
    private val log = LoggerFactory.getLogger(Dvr::class.java)
    private val mutex = Mutex()
    private val running = ConcurrentHashMap<String, Job>()
    private val trigger = Channel<String>(Channel.CONFLATED)
    private val scans = Channel<String>(Channel.UNLIMITED)
    private lateinit var scope: CoroutineScope

    init {
        jobs.register(SCHEDULE_JOB, "Recording schedule", SCHEDULE_EVERY) { requestSchedule("now") }
        jobs.register(CULL_JOB, "Recording cleanup", SCHEDULE_EVERY) { if (::scope.isInitialized) scope.launch { cull("now") } }
    }

    /** Runs the scheduler on guide imports, rule changes and every [SCHEDULE_EVERY], and starts and ends recordings every [TICK]. */
    fun start(scope: CoroutineScope): List<Job> {
        this.scope = scope
        return listOf(
            scope.launch(CoroutineName("dvr-schedule")) {
                while (isActive) {
                    val cause = select<String> { trigger.onReceive { it }; onTimeout(SCHEDULE_EVERY.toMillis()) { "interval" } }
                    try { refreshSchedule(cause) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        log.warn("recording schedule failed class={}", e.javaClass.simpleName)
                    }
                    try { cull(cause) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        log.warn("recording cleanup failed class={}", e.javaClass.simpleName)
                    }
                }
            },
            scope.launch(CoroutineName("dvr-commercials")) {
                // A scan a restart interrupted runs again.
                store.recordings().filter { it.commercials == Wire.CommercialScan.SCANNING }.forEach { scans.trySend(it.id) }
                for (id in scans) {
                    try { scan(id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        log.warn("commercial scan failed id={} class={}", id, e.javaClass.simpleName)
                    }
                }
            },
            scope.launch(CoroutineName("dvr-tick")) {
                while (isActive) {
                    try { tick() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        log.warn("recording tick failed class={}", e.javaClass.simpleName)
                    }
                    delay(TICK.toMillis())
                }
            },
        )
    }

    /** Asks the scheduler to run soon; requests before it runs coalesce. */
    fun requestSchedule(cause: String) { trigger.trySend(cause) }

    // Rules

    suspend fun rules(): List<RecordingRule> = store.recordingRules().map { it.toWire() }

    suspend fun createRule(request: CreateRecordingRule): RecordingRule {
        val title = request.title.trim()
        if (title.isEmpty() || title.length > 200) throw ApiFailure(400, "INVALID_REQUEST", "A rule needs a title of 1 to 200 characters")
        request.channelId?.let { if (lineup.entry(it) == null) throw ApiFailure(404, Wire.ErrorCode.CHANNEL_NOT_FOUND, "Unknown channel") }
        val rule = StoredRule(
            UUID.randomUUID().toString(), title, request.channelId, request.newOnly, keep(request.keep),
            padding(request.startPaddingSeconds, DEFAULT_START_PADDING), padding(request.endPaddingSeconds, DEFAULT_END_PADDING),
            priority(request.priority), clock.instant(),
        )
        store.saveRecordingRule(rule)
        requestSchedule("rule")
        return rule.toWire()
    }

    suspend fun updateRule(id: String, update: UpdateRecordingRule): RecordingRule {
        val rule = store.recordingRule(id) ?: throw ApiFailure(404, "RULE_NOT_FOUND", "Unknown recording rule")
        val changed = rule.copy(
            newOnly = update.newOnly ?: rule.newOnly,
            keep = update.keep?.let(::keep) ?: rule.keep,
            startPadding = update.startPaddingSeconds?.let { padding(it, rule.startPadding) } ?: rule.startPadding,
            endPadding = update.endPaddingSeconds?.let { padding(it, rule.endPadding) } ?: rule.endPadding,
            priority = update.priority?.let(::priority) ?: rule.priority,
        )
        store.saveRecordingRule(changed)
        requestSchedule("rule")
        return changed.toWire()
    }

    /** Deletes the rule and its scheduled recordings; what it recorded stays. */
    suspend fun deleteRule(id: String) {
        if (!store.deleteRecordingRule(id, listOf(Wire.RecordingState.SCHEDULED, CANCELLED))) throw ApiFailure(404, "RULE_NOT_FOUND", "Unknown recording rule")
    }

    // Recordings

    /** Every recording but cancelled ones: scheduled and running soonest first, then the rest newest first, with conflicts marked. */
    suspend fun recordings(): RecordingList {
        val all = store.recordings().filter { it.state != CANCELLED }
        val conflicts = DvrPlanner.conflicts(all, store.recordingRules().associate { it.id to it.priority }, capacity())
        val upcoming = all.filter { it.state == Wire.RecordingState.SCHEDULED || it.state == Wire.RecordingState.RECORDING }.sortedBy { it.recordFrom }
        val past = (all - upcoming.toSet()).sortedByDescending { it.startsAt }
        return RecordingList((upcoming + past).map { it.toWire(it.id in conflicts) }, storage(all))
    }

    suspend fun recording(id: String): Recording = (store.recording(id)?.takeIf { it.state != CANCELLED } ?: throw notFound()).toWire()

    /** The file of a running or finished recording, for playback; null when [id] has recorded nothing. */
    suspend fun playable(id: String): PlayableFile? {
        val recording = store.recording(id) ?: return null
        val relative = recording.path?.takeIf { recording.state == Wire.RecordingState.RECORDING || recording.state == Wire.RecordingState.COMPLETED } ?: return null
        val growing = recording.state == Wire.RecordingState.RECORDING
        return PlayableFile(root.resolve(relative), if (growing) Duration.between(recording.recordFrom, recording.recordUntil).toMillis() else null, growing)
    }

    /** Schedules the programme on [ScheduleRecording.channelId] that starts at [ScheduleRecording.startsAt]. */
    suspend fun schedule(request: ScheduleRecording): Recording {
        val entry = lineup.entry(request.channelId)?.takeIf { it.enabled } ?: throw ApiFailure(404, Wire.ErrorCode.CHANNEL_NOT_FOUND, "Unknown channel")
        val start = try { Instant.parse(request.startsAt) } catch (_: Exception) { throw ApiFailure(400, "INVALID_REQUEST", "Invalid startsAt timestamp") }
        val mapping = entry.guide ?: throw ApiFailure(404, "PROGRAMME_NOT_FOUND", "The channel has no guide")
        val programme = guides.programmes(mapping.guideId, listOf(mapping.channelId), start, start.plusSeconds(1))[mapping.channelId]
            ?.firstOrNull { it.startsAt == start } ?: throw ApiFailure(404, "PROGRAMME_NOT_FOUND", "No program starts then on that channel")
        if (programme.endsAt <= clock.instant()) throw ApiFailure(409, "PROGRAMME_ENDED", "The program has already ended")
        return mutex.withLock {
            store.recordings().firstOrNull { it.channelId == entry.id && it.startsAt == start }?.let { existing ->
                if (existing.state != CANCELLED) return@withLock existing.toWire()
                store.deleteRecording(existing.id)
            }
            val recording = stored(null, Airing(entry.id, entry.name, programme), DEFAULT_START_PADDING, DEFAULT_END_PADDING)
            store.addRecording(recording)
            recording.toWire()
        }.also { scope.launch { tick() } }
    }

    suspend fun update(id: String, update: UpdateRecording): Recording = mutex.withLock {
        val recording = store.recording(id)?.takeIf { it.state != CANCELLED } ?: throw notFound()
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
     * files. A cancelled rule airing is remembered so the rule does not schedule it again.
     */
    suspend fun delete(id: String) {
        val recording = store.recording(id)?.takeIf { it.state != CANCELLED } ?: throw notFound()
        when (recording.state) {
            Wire.RecordingState.RECORDING -> running[id]?.cancelAndJoin() ?: finish(recording, null)
            Wire.RecordingState.SCHEDULED -> mutex.withLock {
                if (recording.ruleId != null) store.saveRecording(recording.copy(state = CANCELLED, updatedAt = clock.instant())) else store.deleteRecording(id)
            }
            else -> mutex.withLock {
                recording.path?.let { removeFiles(it) }
                store.deleteRecording(id)
                log.info("recording deleted id={} title=\"{}\"", id, recording.title)
            }
        }
    }

    // Scheduling

    /** One scheduler pass: adds what the rules match in the guide and drops rule airings the guide no longer has. */
    suspend fun refreshSchedule(cause: String) = jobs.record(SCHEDULE_JOB) {
        val now = clock.instant()
        val rules = store.recordingRules()
        if (rules.isEmpty()) return@record JobOutcome(true, "no rules")
        val airings = airings(rules, now, now.plus(horizon()))
        mutex.withLock {
            val plan = DvrPlanner.plan(rules, airings, store.recordings(), now)
            plan.drop.forEach { store.deleteRecording(it.id) }
            var added = 0
            for ((rule, airing) in plan.add) if (store.addRecording(stored(rule.id, airing, rule.startPadding, rule.endPadding))) added++
            log.info("recording schedule cause={} rules={} added={} dropped={}", cause, rules.size, added, plan.drop.size)
            JobOutcome(true, "$added added, ${plan.drop.size} dropped")
        }
    }

    /** The guide's airings in [from, to) on the channels the rules name, or on every enabled channel with a guide. */
    private suspend fun airings(rules: List<StoredRule>, from: Instant, to: Instant): List<Airing> {
        val anyChannel = rules.any { it.channelId == null }
        val wanted = rules.mapNotNullTo(HashSet()) { it.channelId }
        val titles = rules.mapTo(HashSet()) { it.title.trim().lowercase() }
        val entries = lineup.entries().filter { it.enabled && it.guide != null && (anyChannel || it.id in wanted) }
        return entries.groupBy { it.guide!!.guideId }.flatMap { (guideId, group) ->
            val rows = guides.programmes(guideId, group.map { it.guide!!.channelId }.distinct(), from, to)
            group.flatMap { entry ->
                rows[entry.guide!!.channelId].orEmpty().filter { it.title.trim().lowercase() in titles }.map { Airing(entry.id, entry.name, it) }
            }
        }
    }

    // Recording

    /** Starts the recordings whose window has opened, fails the ones whose window passed unrecorded, and resumes any a restart left running. */
    suspend fun tick() {
        val now = clock.instant()
        mutex.withLock {
            val all = store.recordings()
            for (missed in all.filter { it.state == Wire.RecordingState.SCHEDULED && it.recordUntil <= now }) {
                store.saveRecording(missed.copy(state = Wire.RecordingState.FAILED, reason = "MISSED", updatedAt = now))
                log.warn("recording missed id={} title=\"{}\"", missed.id, missed.title)
            }
            val due = all.filter { (it.state == Wire.RecordingState.SCHEDULED || it.state == Wire.RecordingState.RECORDING) && it.recordFrom <= now && now < it.recordUntil && !running.containsKey(it.id) }
            // Earliest first, so when slots run short the recording whose window opened first gets one.
            for (recording in due.sortedBy { it.recordFrom }) launchRecording(recording)
        }
        for (orphan in store.recordings().filter { it.state == Wire.RecordingState.RECORDING && it.recordUntil <= now && !running.containsKey(it.id) }) finish(orphan, null)
    }

    private fun launchRecording(recording: StoredRecording) {
        val job = scope.launch(CoroutineName("recording:${recording.id}"), start = CoroutineStart.LAZY) {
            var current = recording
            var take: RecordedTake? = null
            try {
                current = mutex.withLock {
                    val relative = current.path?.takeIf { it.startsWith(RecordingLayout.IN_PROGRESS) }?.let(Path::of)
                        ?: RecordingLayout.inProgress(RecordingLayout.path(current.name, current.startsAt, zone) { taken(it) })
                    current.copy(state = Wire.RecordingState.RECORDING, reason = null, path = relative.toString(), updatedAt = clock.instant())
                        .also { store.saveRecording(it) }
                }
                log.info("recording started id={} channel={} title=\"{}\"", current.id, current.channelId, current.title)
                take = recorder.record(current.channelId, root.resolve(current.path!!), current.recordUntil)
            } finally {
                withContext(NonCancellable) { finish(current, take) }
            }
        }
        running[recording.id] = job
        job.invokeOnCompletion { running.remove(recording.id, job) }
        job.start()
    }

    /** Moves the file into place and records the outcome; an empty or missing file fails the recording and is removed. */
    private suspend fun finish(recording: StoredRecording, take: RecordedTake?) = mutex.withLock {
        val now = clock.instant()
        val latest = store.recording(recording.id) ?: return@withLock
        val partial = latest.path?.let { root.resolve(it) }
        val bytes = withContext(Dispatchers.IO) { partial?.takeIf(Files::exists)?.let(Files::size) ?: 0L }
        if (partial == null || bytes == 0L) {
            withContext(Dispatchers.IO) { partial?.let(Files::deleteIfExists) }
            store.saveRecording(latest.copy(state = Wire.RecordingState.FAILED, reason = take?.lastFailure ?: "NO_MEDIA", path = null, bytes = 0, updatedAt = now))
            log.warn("recording failed id={} title=\"{}\" reason={}", latest.id, latest.title, take?.lastFailure ?: "NO_MEDIA")
            return@withLock
        }
        val final = RecordingLayout.path(latest.name, latest.startsAt, zone) { taken(it, except = latest.path) }
        withContext(Dispatchers.IO) {
            Files.createDirectories(root.resolve(final).parent)
            Files.move(partial, root.resolve(final), StandardCopyOption.ATOMIC_MOVE)
            pruneEmpty(partial.parent, root)
        }
        // A recording cut short by a stop or a source that never came back is still kept; the reason says why.
        val short = now < latest.recordUntil.minusSeconds(5)
        val scan = if (commercials != null) Wire.CommercialScan.SCANNING else null
        store.saveRecording(latest.copy(
            state = Wire.RecordingState.COMPLETED, reason = if (short) take?.lastFailure ?: "STOPPED" else null, path = final.toString(), bytes = bytes,
            commercials = scan, updatedAt = now,
        ))
        if (scan != null) scans.trySend(latest.id)
        log.info("recording finished id={} title=\"{}\" bytes={} path=\"{}\"", latest.id, latest.title, bytes, final)
    }.also { if (::scope.isInitialized) scope.launch { cull("finished") } }

    // Commercials

    /** Finds the breaks in one finished recording and keeps them, unless it was deleted meanwhile. */
    private suspend fun scan(id: String) {
        val detector = commercials ?: return
        val recording = store.recording(id)?.takeIf { it.state == Wire.RecordingState.COMPLETED && it.path != null } ?: return
        val outcome = try {
            Wire.CommercialScan.DONE to detector.detect(root.resolve(recording.path!!))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.warn("commercial scan failed id={} title=\"{}\" class={} message=\"{}\"", id, recording.title, e.javaClass.simpleName, redactedMessage(e))
            Wire.CommercialScan.FAILED to emptyList()
        }
        mutex.withLock {
            val latest = store.recording(id)?.takeIf { it.path == recording.path } ?: return@withLock
            store.saveRecording(latest.copy(commercials = outcome.first, breaks = outcome.second, updatedAt = clock.instant()))
        }
        if (outcome.first == Wire.CommercialScan.DONE) log.info("commercial scan id={} title=\"{}\" breaks={}", id, recording.title, outcome.second.size)
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
            plan.forEach { (recording, reason) -> remove(recording, reason) }
            val gone = plan.mapTo(HashSet()) { it.first.id }
            val floor = keep.freeGib.toLong() * GIB
            val oldest = DvrPlanner.byCullOrder(all.filter { it.id !in gone }).iterator()
            while (oldest.hasNext()) {
                val free = withContext(Dispatchers.IO) { Files.createDirectories(root); freeSpace(root) } ?: break
                if (free >= floor) break
                val recording = oldest.next()
                remove(recording, "SPACE")
                gone += recording.id
            }
            gone.size
        }
        JobOutcome(true, "$deleted deleted").also { if (deleted > 0) log.info("recording cleanup cause={} deleted={}", cause, deleted) }
    }

    private suspend fun remove(recording: StoredRecording, reason: String) {
        withContext(Dispatchers.IO) { recording.path?.let { removeFiles(it) } }
        store.deleteRecording(recording.id)
        log.info("recording culled id={} title=\"{}\" reason={}", recording.id, recording.title, reason)
    }

    /** True when a file on disk takes [relative], in place or in progress; [except] is the caller's own in-progress path. */
    private fun taken(relative: Path, except: String? = null): Boolean {
        val running = RecordingLayout.inProgress(relative)
        return Files.exists(root.resolve(relative)) || (running.toString() != except && Files.exists(root.resolve(running)))
    }

    private fun removeFiles(relative: String) {
        val file = root.resolve(relative).normalize()
        if (!file.startsWith(root.normalize())) return
        Files.deleteIfExists(file)
        Files.deleteIfExists(Commercials.sidecar(file))
        pruneEmpty(file.parent, root)
    }

    /** Removes [directory] and its parents while they are empty, stopping at [stop]. */
    private fun pruneEmpty(directory: Path?, stop: Path) {
        var current = directory
        while (current != null && current.normalize() != stop.normalize() && current.startsWith(root)) {
            val empty = Files.isDirectory(current) && Files.list(current).use { !it.findAny().isPresent }
            if (!empty) return
            Files.delete(current)
            current = current.parent
        }
    }

    private fun storage(all: List<StoredRecording>): RecordingStorage {
        val used = all.filter { it.state == Wire.RecordingState.COMPLETED || it.state == Wire.RecordingState.RECORDING }.sumOf { it.bytes }
        val store = runCatching { Files.createDirectories(root); Files.getFileStore(root) }.getOrNull() ?: return RecordingStorage(usedBytes = used)
        return RecordingStorage(store.usableSpace, store.totalSpace, used)
    }

    private fun stored(ruleId: String?, airing: Airing, startPadding: Duration, endPadding: Duration): StoredRecording {
        val programme: GuideProgramme = airing.programme
        val name = RecordingTitle.of(programme)
        val now = clock.instant()
        return StoredRecording(
            UUID.randomUUID().toString(), ruleId, airing.channelId, airing.channelName, programme.startsAt, programme.endsAt, startPadding, endPadding,
            programme.title, name.episodeTitle, programme.description, name.numbering?.season, name.numbering?.episode, name.movie, name.year, programme.icon,
            Wire.RecordingState.SCHEDULED, createdAt = now, updatedAt = now,
        )
    }

    private fun notFound() = ApiFailure(404, "RECORDING_NOT_FOUND", "Unknown recording")

    private fun keep(value: Int): Int = value.takeIf { it in 0..1000 } ?: throw ApiFailure(400, "INVALID_REQUEST", "keep must be 0 to 1000")
    private fun priority(value: Int): Int = value.takeIf { it in -100..100 } ?: throw ApiFailure(400, "INVALID_REQUEST", "priority must be -100 to 100")
    private fun padding(seconds: Int?, default: Duration): Duration =
        seconds?.let { if (it in 0..MAX_PADDING_SECONDS) Duration.ofSeconds(it.toLong()) else throw ApiFailure(400, "INVALID_REQUEST", "Padding must be 0 to $MAX_PADDING_SECONDS seconds") } ?: default

    companion object {
        const val SCHEDULE_JOB = "dvr-schedule"
        const val CULL_JOB = "dvr-cull"
        private const val GIB = 1024L * 1024 * 1024
        val DEFAULT_START_PADDING: Duration = Duration.ofMinutes(1)
        val DEFAULT_END_PADDING: Duration = Duration.ofMinutes(2)
        const val MAX_PADDING_SECONDS = 3600
        val SCHEDULE_EVERY: Duration = Duration.ofMinutes(30)
        val TICK: Duration = Duration.ofSeconds(5)
    }
}
