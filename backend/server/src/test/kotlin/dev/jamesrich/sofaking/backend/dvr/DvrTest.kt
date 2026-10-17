package dev.jamesrich.sofaking.backend.dvr

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.addRecording
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.*

/** The DVR end to end on the live HLS fixture: a rule schedules from the guide, FFmpeg records, the file lands in place. */
class DvrTest {
    private val xmltvTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    /** An XMLTV guide for the fixture's live channel with one short programme per [programmes] entry. */
    private fun guideServer(programmes: List<Triple<String, Instant, Instant>>): HttpServer {
        val body = buildString {
            append("<?xml version=\"1.0\"?><tv><channel id=\"live\"><display-name>Live</display-name></channel>")
            for ((title, start, end) in programmes) {
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
                val done = withTimeout(60_000) {
                    while (true) {
                        val current = dvr.recording(scheduled.id)
                        if (current.state == Wire.RecordingState.COMPLETED || current.state == Wire.RecordingState.FAILED) return@withTimeout current
                        delay(200)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
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
                val scanned = withTimeout(30_000) {
                    while (dvr.recording(done.id).commercials == Wire.CommercialScan.SCANNING) delay(100)
                    dvr.recording(done.id)
                }
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

    @Test fun `a one-off is scheduled from the guide, cancelling it forgets it, and a rule airing stays cancelled`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-dvr-api-")
        val start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600)
        val guide = guideServer(listOf(Triple("Movie Night", start, start.plusSeconds(7200)), Triple("Movie Night", start.plusSeconds(86_400), start.plusSeconds(93_600))))
        MediaFixture().use { media ->
            val runtime = runtime(dir, media, guide)
            try {
                val dvr = assertNotNull(runtime.dvr)
                val live = runtime.lineup.entries().single { it.number == "2" }
                val failure = assertFailsWith<dev.jamesrich.sofaking.backend.catalog.ApiFailure> { dvr.schedule(ScheduleRecording(live.id, start.plusSeconds(60).toString())) }
                assertEquals("PROGRAMME_NOT_FOUND", failure.code)
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
                    runtime.store, runtime.lineup, runtime.guides, Recorder("ffmpeg", runtime.sessions, runtime.feeds, Clock.systemUTC()), root, Clock.systemUTC(),
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
}
