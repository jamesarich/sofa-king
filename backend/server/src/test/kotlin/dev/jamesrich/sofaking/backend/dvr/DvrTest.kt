package dev.jamesrich.sofaking.backend.dvr

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.pollFor
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.recording
import dev.jamesrich.sofaking.backend.store.recordings
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/** The DVR end to end on the live HLS fixture: a rule schedules from the guide, FFmpeg records, the file lands in place. */
class DvrTest {
    private val xmltvTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    /** An XMLTV guide for the fixture's live channel with one short program per [programs] entry. */
    private fun guideServer(programs: List<Triple<String, Instant, Instant>>): HttpServer {
        val body = buildString {
            append("<?xml version=\"1.0\"?><tv><channel id=\"live\"><display-name>Live</display-name></channel>")
            for ((title, start, end) in programs) {
                append("<programme channel=\"live\" start=\"${xmltvTime.format(start)}\" stop=\"${xmltvTime.format(end)}\"><title>$title</title></programme>")
            }
            append("</tv>")
        }.toByteArray()
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/guide.xml") { exchange -> exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) } }
            start()
        }
    }

    private suspend fun runtime(dir: Path, media: MediaFixture, guide: HttpServer): LiveRuntime {
        val feed = media.feed.copy(guideUrl = "http://127.0.0.1:${guide.address.port}/guide.xml")
        val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(listOf(feed)))
        val config = ServerConfig(
            "127.0.0.1", 8080, dir, sources, allowLoopbackUpstreams = true, recordingsDirectory = dir.resolve("recordings"),
            comskip = CommercialsTest.fakeComskip(dir, "1.0\t2.5\t3").toString(),
        )
        return LiveRuntime.create(openStore(dir), config, Clock.systemUTC(), probes = false).also { it.feeds.refresh(feed); it.adoptAll() }
    }

    @Test fun `a series rule records the live channel into the TV folder and leaves nothing in progress`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-dvr-")
        val start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(2)
        val guide = guideServer(listOf(Triple("Test Show", start, start.plusSeconds(6)), Triple("Other", start.plusSeconds(6), start.plusSeconds(600))))
        MediaFixture().use { media ->
            val runtime = runtime(dir, media, guide)
            try {
                val dvr = assertNotNull(runtime.dvr)
                val live = runtime.lineup.entries().single { it.number == "2" }
                val rule = dvr.createRule(CreateRecordingRule("test show", channelId = live.id, startPaddingSeconds = 0, endPaddingSeconds = 0))
                dvr.refreshSchedule("test")
                val scheduled = dvr.recordings().recordings.single()
                assertEquals(Wire.RecordingState.SCHEDULED, scheduled.state)
                assertEquals(rule.id, scheduled.ruleId)
                assertEquals("Test Show", scheduled.title)
                val done = pollFor(60_000, 200) {
                    dvr.recording(scheduled.id).takeIf { it.state == Wire.RecordingState.COMPLETED || it.state == Wire.RecordingState.FAILED }
                }
                assertEquals(Wire.RecordingState.COMPLETED, done.state, done.reason)
                val path = assertNotNull(done.path)
                assertTrue(path.startsWith("TV/Test Show/Season "), path)
                val file = dir.resolve("recordings").resolve(path)
                val bytes = Files.readAllBytes(file)
                assertEquals(bytes.size.toLong(), done.bytes)
                assertTrue(bytes.size > 188 * 50, "recorded ${bytes.size} bytes")
                assertEquals(0x47.toByte(), bytes[0])
                assertFalse(Files.exists(dir.resolve("recordings/.inprogress")), "the in-progress folder is pruned")
                assertEquals(0, runtime.sessions.count())

                // The commercial scan keeps the breaks and the EDL beside the file.
                pollUntil(30_000, 100) { dvr.recording(done.id).commercials != Wire.CommercialScan.SCANNING }
                val scanned = dvr.recording(done.id)
                assertEquals(Wire.CommercialScan.DONE, scanned.commercials)
                assertEquals(listOf(RecordingBreak(1000, 2500)), scanned.breaks)
                assertTrue(Files.exists(Commercials.sidecar(file)))

                // A second pass keeps the one it made and schedules nothing more for the ended airing.
                dvr.refreshSchedule("again")
                assertEquals(listOf(scheduled.id), dvr.recordings().recordings.map { it.id })

                // Deleting a kept recording removes its file and the empty folders above it.
                dvr.delete(done.id)
                assertFalse(Files.exists(file))
                assertFalse(Files.exists(dir.resolve("recordings/TV")))
                assertTrue(dvr.recordings().recordings.isEmpty())
            } finally {
                runtime.close(); runtime.store.close(); guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `a one-off is scheduled from the guide, canceling it forgets it, and a rule airing stays canceled`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-dvr-api-")
        val start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600)
        val guide = guideServer(listOf(Triple("Movie Night", start, start.plusSeconds(7200)), Triple("Movie Night", start.plusSeconds(86_400), start.plusSeconds(93_600))))
        MediaFixture().use { media ->
            val runtime = runtime(dir, media, guide)
            try {
                val dvr = assertNotNull(runtime.dvr)
                val live = runtime.lineup.entries().single { it.number == "2" }
                val failure = assertFailsWith<dev.jamesrich.sofaking.backend.catalog.ApiFailure> { dvr.schedule(ScheduleRecording(live.id, start.plusSeconds(60).toString())) }
                assertEquals("PROGRAM_NOT_FOUND", failure.code)
                val oneOff = dvr.schedule(ScheduleRecording(live.id, start.toString()))
                assertEquals(oneOff.id, dvr.schedule(ScheduleRecording(live.id, start.toString())).id, "scheduling twice is one recording")
                assertEquals(60, oneOff.startPaddingSeconds)
                dvr.delete(oneOff.id)
                assertTrue(dvr.recordings().recordings.isEmpty())

                dvr.createRule(CreateRecordingRule("Movie Night"))
                dvr.refreshSchedule("test")
                val first = dvr.recordings().recordings
                assertEquals(2, first.size)
                dvr.delete(first.first().id)
                dvr.refreshSchedule("again")
                assertEquals(listOf(first.last().id), dvr.recordings().recordings.map { it.id })

                val protected = dvr.update(first.last().id, UpdateRecording(protected = true))
                assertTrue(protected.protected)
            } finally {
                runtime.close(); runtime.store.close(); guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    // A 20-second recording window across a restart, with waits of up to 90 seconds before it fails.
    @Test @Timeout(2, unit = TimeUnit.MINUTES)
    fun `a shutdown mid-window leaves the recording running, and the next start resumes it into the same file`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-dvr-resume-")
        val guide = guideServer(emptyList())
        MediaFixture().use { media ->
            val first = runtime(dir, media, guide)
            val (id, partial) = try {
                val dvr = assertNotNull(first.dvr)
                val live = first.lineup.entries().single { it.number == "2" }
                val start = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                val id = dvr.schedule(ScheduleRecording(live.id, start.toString(), start.plusSeconds(20).toString(), "Resumed")).id
                id to pollFor(30_000, 100) {
                    first.store.recordings().single().path?.let { dir.resolve("recordings").resolve(it) }?.takeIf { Files.exists(it) && Files.size(it) > 0 }
                }
            } finally {
                first.close(); first.store.close()
            }
            val before = openStore(dir).use { store ->
                val stopped = store.recordings().single()
                assertEquals(Wire.RecordingState.RECORDING, stopped.state)
                assertEquals(partial, dir.resolve("recordings").resolve(assertNotNull(stopped.path)))
                Files.size(partial)
            }
            val second = runtime(dir, media, guide)
            try {
                val dvr = assertNotNull(second.dvr)
                val done = pollFor(60_000, 200) {
                    dvr.recording(id).takeIf { it.state == Wire.RecordingState.COMPLETED || it.state == Wire.RecordingState.FAILED }
                }
                assertEquals(Wire.RecordingState.COMPLETED, done.state, done.reason)
                assertTrue(done.bytes > before, "resumed ${done.bytes} bytes over $before")
                assertFalse(Files.exists(partial))
            } finally {
                second.close(); second.store.close(); guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `a manual recording takes a time window on a channel without a guide`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-dvr-manual-")
        val guide = guideServer(emptyList())
        MediaFixture().use { media ->
            val runtime = runtime(dir, media, guide)
            try {
                val dvr = assertNotNull(runtime.dvr)
                val live = runtime.lineup.entries().single { it.number == "2" }
                val start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600)
                val guideless = assertFailsWith<dev.jamesrich.sofaking.backend.catalog.ApiFailure> { dvr.schedule(ScheduleRecording(live.id, start.toString())) }
                assertEquals("PROGRAM_NOT_FOUND", guideless.code)
                val tooLong = assertFailsWith<dev.jamesrich.sofaking.backend.catalog.ApiFailure> {
                    dvr.schedule(ScheduleRecording(live.id, start.toString(), start.plus(java.time.Duration.ofHours(13)).toString()))
                }
                assertEquals(400, tooLong.status)
                val manual = dvr.schedule(ScheduleRecording(live.id, start.toString(), start.plusSeconds(1800).toString(), "  Late Game  "))
                assertEquals("Late Game", manual.title)
                assertEquals(Wire.RecordingState.SCHEDULED, manual.state)
                assertEquals(start.plusSeconds(1800).toString(), manual.endsAt)
                assertEquals(0, manual.startPaddingSeconds)
                assertEquals(0, manual.endPaddingSeconds)
                val untitled = dvr.schedule(ScheduleRecording(live.id, start.plusSeconds(7200).toString(), start.plusSeconds(9000).toString()))
                assertEquals(live.name, untitled.title)
            } finally {
                runtime.close(); runtime.store.close(); guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `a rule edit moves it to another channel or to any channel`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-dvr-rule-")
        val guide = guideServer(emptyList())
        MediaFixture().use { media ->
            val runtime = runtime(dir, media, guide)
            try {
                val dvr = assertNotNull(runtime.dvr)
                val live = runtime.lineup.entries().single { it.number == "2" }
                val rule = dvr.createRule(CreateRecordingRule("News"))
                assertEquals(live.id, dvr.updateRule(rule.id, UpdateRecordingRule(channelId = live.id, priority = 5)).channelId)
                val any = dvr.updateRule(rule.id, UpdateRecordingRule(anyChannel = true))
                assertNull(any.channelId)
                assertEquals(5, any.priority)
                val unknown = assertFailsWith<dev.jamesrich.sofaking.backend.catalog.ApiFailure> { dvr.updateRule(rule.id, UpdateRecordingRule(channelId = "nope")) }
                assertEquals(404, unknown.status)
            } finally {
                runtime.close(); runtime.store.close(); guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `a recording that runs the disk low makes cleanup free space, then stops with DISK_FULL`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-dvr-full-")
        val start = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val guide = guideServer(listOf(Triple("Long Show", start.minusSeconds(5), start.plusSeconds(600))))
        MediaFixture().use { media ->
            val runtime = runtime(dir, media, guide)
            try {
                val root = dir.resolve("recordings")
                val gib = 1024L * 1024 * 1024
                // Free space falls once the recording has written anything: the disk is full apart from what cleanup frees.
                var free = 20 * gib
                // Its own store, so the runtime's DVR never sees, and records, the same recording.
                val store = openStore(dir.resolve("own"))
                val dvr = Dvr(
                    store, runtime.lineup, runtime.guides, Recorder("ffmpeg", runtime.sessions, runtime.feeds, Clock.systemUTC(), runtime.loopback), root, Clock.systemUTC(),
                    { 4 }, retention = { Retention(freeGib = 5) }, freeSpace = { free },
                )
                kotlinx.coroutines.coroutineScope {
                    val jobs = dvr.start(this)
                    try {
                        val live = runtime.lineup.entries().single { it.number == "2" }
                        val old = start.minus(java.time.Duration.ofDays(3))
                        Files.createDirectories(root.resolve("TV/Old")); Files.write(root.resolve("TV/Old/old.ts"), byteArrayOf(0x47))
                        store.addRecording(StoredRecording(
                            "old", null, live.id, "Live", old, old.plusSeconds(1800), java.time.Duration.ZERO, java.time.Duration.ZERO, "Old",
                            state = Wire.RecordingState.COMPLETED, path = "TV/Old/old.ts", bytes = 1, createdAt = old, updatedAt = old,
                        ))
                        val running = dvr.schedule(ScheduleRecording(live.id, start.minusSeconds(5).toString(), start.plusSeconds(600).toString(), "Long Show"))
                        pollUntil(30_000, 100) { dvr.recording(running.id).state == Wire.RecordingState.RECORDING }
                        // Protected, so the cleanup after it stops keeps it for the assertions below.
                        dvr.update(running.id, UpdateRecording(protected = true))
                        free = 100L * 1024 * 1024
                        pollUntil(60_000, 200) { dvr.recording(running.id).state != Wire.RecordingState.RECORDING }
                        val stopped = dvr.recording(running.id)
                        assertEquals(Dvr.DISK_FULL, stopped.reason)
                        assertFalse(Files.exists(root.resolve("TV/Old/old.ts")), "cleanup deleted the finished recording first")
                        assertEquals(listOf(running.id), dvr.recordings().recordings.map { it.id })
                    } finally {
                        jobs.forEach { it.cancel() }
                    }
                }
                store.close()
            } finally {
                runtime.close(); runtime.store.close(); guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `cleanup deletes past the keep count, past the keep days, then oldest first until the space floor holds`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-dvr-cull-")
        val guide = guideServer(emptyList())
        MediaFixture().use { media ->
            val runtime = runtime(dir, media, guide)
            try {
                val root = dir.resolve("recordings")
                val gib = 1024L * 1024 * 1024
                // A disk of 12 GiB where each recording's one-byte file stands for 1 GiB.
                fun free() = 12 * gib - Files.walk(root).use { files -> files.filter(Files::isRegularFile).count() } * gib
                val dvr = Dvr(
                    runtime.store, runtime.lineup, runtime.guides, Recorder("ffmpeg", runtime.sessions, runtime.feeds, Clock.systemUTC(), runtime.loopback), root, Clock.systemUTC(),
                    { 4 }, retention = { Retention(freeGib = 5, keepDays = 30) }, freeSpace = { free() },
                )
                val rule = dvr.createRule(CreateRecordingRule("News", keep = 2))
                val now = Instant.now()
                fun completed(id: String, daysAgo: Long, ruleId: String? = null, protected: Boolean = false, watched: Boolean = false): StoredRecording {
                    val start = now.minus(java.time.Duration.ofDays(daysAgo))
                    val path = "TV/Show/$id.ts"
                    Files.createDirectories(root.resolve(path).parent); Files.write(root.resolve(path), byteArrayOf(0x47))
                    return StoredRecording(
                        id, ruleId, "c", "C", start, start.plusSeconds(1800), java.time.Duration.ZERO, java.time.Duration.ZERO, id,
                        state = Wire.RecordingState.COMPLETED, path = path, bytes = 1, protected = protected, watched = watched, createdAt = start, updatedAt = start,
                    )
                }
                val all = listOf(
                    completed("news-1", 1, rule.id), completed("news-2", 2, rule.id), completed("news-3", 3, rule.id), completed("news-4", 4, rule.id, protected = true),
                    completed("old", 40), completed("old-kept", 50, protected = true),
                    completed("a", 10), completed("b", 9, watched = true), completed("c", 8), completed("d", 7), completed("e", 6),
                )
                all.forEach { runtime.store.addRecording(it) }
                // 11 files leave 1 GiB free: KEEP takes news-3, AGE takes old, then SPACE takes b (watched) and a to reach 5 GiB.
                dvr.cull("test")
                val left = dvr.recordings().recordings.map { it.id }.toSet()
                assertEquals(setOf("news-1", "news-2", "news-4", "old-kept", "c", "d", "e"), left)
                assertEquals(5 * gib, free())
                assertFalse(Files.exists(root.resolve("TV/Show/old.ts")))
            } finally {
                runtime.close(); runtime.store.close(); guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    private class Ticking(val dvr: Dvr, val store: dev.jamesrich.sofaking.backend.store.BackendStore, val clock: MutableClock, val root: Path)

    /**
     * A DVR on its own store and an injected clock, never started: each [Dvr.tick] is the test's, as after a restart.
     * The runtime only lends it a lineup, guides and a recorder; its own DVR runs on another store.
     */
    private fun ticking(block: suspend Ticking.() -> Unit) = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-dvr-tick-")
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config = ServerConfig("127.0.0.1", 8080, dir, dir.resolve("sources.json"), updateImage = null, comskip = null)
        val runtime = LiveRuntime.create(openStore(dir), config, Clock.systemUTC(), probes = false)
        val clock = MutableClock()
        val store = openStore(dir.resolve("own"), clock)
        val root = dir.resolve("own/recordings")
        try {
            val dvr = Dvr(store, runtime.lineup, runtime.guides, Recorder("ffmpeg", runtime.sessions, runtime.feeds, clock, runtime.loopback), root, clock, { 4 }, zone = ZoneOffset.UTC)
            Ticking(dvr, store, clock, root).block()
        } finally {
            store.close(); runtime.close(); runtime.store.close(); dir.toFile().deleteRecursively()
        }
    }

    private fun stored(id: String, start: Instant, state: String, path: String? = null) = StoredRecording(
        id, null, "c", "Channel C", start, start.plusSeconds(1800), java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(120), "News",
        state = state, path = path, createdAt = start, updatedAt = start,
    )

    @Test fun `a scheduled recording whose window passed while the server was off fails as MISSED on the first tick`() = ticking {
        val start = clock.now.plusSeconds(3600)
        store.addRecording(stored("missed", start, Wire.RecordingState.SCHEDULED))
        store.addRecording(stored("later", start.plus(java.time.Duration.ofDays(1)), Wire.RecordingState.SCHEDULED))
        dvr.tick()
        assertEquals(Wire.RecordingState.SCHEDULED, dvr.recording("missed").state, "its window has not opened yet")

        // The server is off from before the window opens until after it closes.
        clock.now = start.plusSeconds(1800 + 120)
        dvr.tick()
        val missed = dvr.recording("missed")
        assertEquals(Wire.RecordingState.FAILED, missed.state)
        assertEquals("MISSED", missed.reason)
        assertNull(missed.path)
        assertEquals(Wire.RecordingState.SCHEDULED, dvr.recording("later").state)
    }

    @Test fun `a recording a restart left running past its window is finished from what it wrote`() = ticking {
        val start = clock.now.minusSeconds(7200)
        val name = RecordingTitle("News")
        val final = RecordingLayout.path(name, start, ZoneOffset.UTC) { false }
        val partial = RecordingLayout.inProgress(final)
        Files.createDirectories(root.resolve(partial).parent)
        Files.write(root.resolve(partial), ByteArray(188 * 10) { if (it % 188 == 0) 0x47 else 0 })
        store.addRecording(stored("orphan", start, Wire.RecordingState.RECORDING, partial.toString()))

        dvr.tick()
        val done = dvr.recording("orphan")
        assertEquals(Wire.RecordingState.COMPLETED, done.state)
        assertNull(done.reason, "the window ran to its end")
        assertEquals(final.toString(), done.path)
        assertEquals(188L * 10, done.bytes)
        assertEquals(188L * 10, Files.size(root.resolve(final)))
        assertFalse(Files.exists(root.resolve(RecordingLayout.IN_PROGRESS)), "the in-progress folder is pruned")
    }

    @Test fun `canceling a rule airing while the tick starts it keeps it canceled`() = ticking {
        // Recordings launch on this test's event loop, with the DVR's own loops stopped, so only these two calls interleave.
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        dvr.start(scope).forEach { it.cancel() }
        try {
            store.addRecording(stored("airing", clock.now.minusSeconds(30), Wire.RecordingState.SCHEDULED).copy(ruleId = "rule"))
            coroutineScope {
                launch { dvr.tick() }
                launch { dvr.delete("airing") }
            }
            pollUntil(10_000) { store.recording("airing")?.state.let { it == CANCELED || it == Wire.RecordingState.FAILED } }
            assertEquals(CANCELED, store.recording("airing")?.state, "the tick does not undo the cancel")
            assertFalse(Files.exists(root.resolve(RecordingLayout.IN_PROGRESS)), "nothing is recorded")
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a recording that wrote nothing fails as NO_MEDIA and leaves no file behind`() = ticking {
        val start = clock.now.minusSeconds(7200)
        val empty = RecordingLayout.inProgress(RecordingLayout.path(RecordingTitle("News"), start, ZoneOffset.UTC) { false })
        Files.createDirectories(root.resolve(empty).parent)
        Files.createFile(root.resolve(empty))
        store.addRecording(stored("empty", start, Wire.RecordingState.RECORDING, empty.toString()))
        store.addRecording(stored("pathless", start.plusSeconds(60), Wire.RecordingState.RECORDING))

        dvr.tick()
        for (id in listOf("empty", "pathless")) {
            val failed = dvr.recording(id)
            assertEquals(Wire.RecordingState.FAILED, failed.state, id)
            assertEquals("NO_MEDIA", failed.reason, id)
            assertNull(failed.path, id)
            assertEquals(0, failed.bytes, id)
        }
        assertFalse(Files.exists(root.resolve(empty)))
    }
}
