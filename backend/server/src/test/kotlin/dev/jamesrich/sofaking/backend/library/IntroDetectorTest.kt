package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraryIntros
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveLibraryFingerprint
import dev.jamesrich.sofaking.contract.MediaSegment
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Episodes made of tunes through the real FFmpeg, as CI and the image run it. */
class IntroDetectorTest {
    private class Setup(
        val root: Path,
        val store: BackendStore,
        val service: LibraryService,
        val detector: IntroDetector,
        val reads: MutableList<Path>,
    )

    /** Episodes the scan takes for ten minutes long unless [durations] knows a file's length. */
    private fun setup(tailShare: Double = TAIL_SHARE, durations: Map<String, Long> = emptyMap()): Setup = runBlocking {
        val dir = Files.createTempDirectory("sofa-intros-")
        val root = Files.createDirectories(dir.resolve("media"))
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        val jobs = Jobs(clock)
        val reads = ArrayList<Path>()
        val ffmpeg = FfmpegFingerprinter("ffmpeg", dir.resolve("work"))
        val detector =
            IntroDetector(store, clock, jobs, { file, from, seconds ->
                synchronized(reads) { reads.add(file) }
                ffmpeg.fingerprint(file, from, seconds)
            }, tailShare)
        // Ten-minute episodes as far as the scan knows, so the whole of each short test file is read for its intro.
        val scanner = LibraryScanner({
            MediaInfo(
                "matroska,webm",
                durationMs =
                durations[it.fileName.toString()] ?: 600_000,
            )
        }, missingGrace = Duration.ofDays(7))
        Setup(
            root,
            store,
            LibraryService(store, clock, listOf(MediaRoot("Media", root)), scanner, jobs, intros = detector),
            detector,
            reads,
        )
    }

    /** [seconds] of a tune of random notes with two overtones, from [seed]: unlike any other seed's to Chromaprint. */
    private fun tune(seed: Int, seconds: Double): ShortArray {
        val random = Random(seed)
        val out = ArrayList<Short>()
        var t = 0.0
        while (t < seconds) {
            val length = listOf(0.25, 0.375, 0.5)[random.nextInt(3)]
            val frequency = 440.0 * 2.0.pow((random.nextInt(48, 85) - 69) / 12.0)
            val samples = (length * RATE).toInt()
            for (i in 0 until samples) {
                val x = i.toDouble() / RATE
                val envelope = min(1.0, min(x * 40, (length - x) * 40))
                val wave = (1..3).sumOf { k -> sin(2 * PI * frequency * k * x) / k }
                out += (wave * envelope * 6_000).toInt().toShort()
            }
            t += length
        }
        return out.toShortArray()
    }

    /** An AAC episode in Matroska: [before] seconds of its own tune, the [theme], then a minute of its own. */
    private fun episode(file: Path, seed: Int, before: Double, theme: ShortArray) = encode(
        file,
        tune(seed, before) + theme + tune(seed + 1_000, 60.0),
    )

    private fun encode(file: Path, pcm: ShortArray) {
        val wav = Files.createTempFile("episode-", ".wav")
        Files.write(wav, wav(pcm))
        Files.createDirectories(file.parent)
        val exit = ProcessBuilder("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", wav.toString(), "-c:a", "aac", "-b:a", "96k", file.toString())
            .inheritIO().start().waitFor()
        Files.delete(wav)
        assertEquals(0, exit, "ffmpeg encodes the test episode")
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            pcm.forEach(::putShort)
        }.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + data.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(RATE)
            putInt(RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(data.size)
        }.array()
        return ByteArrayOutputStream().apply {
            write(header)
            write(data)
        }.toByteArray()
    }

    private fun assertNear(expectedMs: Long, actualMs: Long) =
        assertTrue(abs(expectedMs - actualMs) <= 2_500, "expected about $expectedMs ms, was $actualMs ms")

    @Test fun `episodes that play the same theme get it as their intro, and a file is read once`() = runBlocking {
        val s = setup()
        val tv = s.root.resolve("tv")
        val theme = tune(1, 20.0)
        val starts = mapOf(1 to 5.0, 2 to 12.0, 3 to 0.0, 4 to 25.0)
        starts.forEach { (number, before) ->
            episode(tv.resolve("Show/Season 01/Show - S01E0$number.mkv"), 100 + number, before, theme)
        }
        // A show of one episode has nothing to compare with.
        episode(tv.resolve("Lonely/Season 01/Lonely - S01E01.mkv"), 300, 5.0, theme)
        val library = s.service.create("TV", LibraryKind.SHOWS, tv.toString())
        s.service.scan(library.id)

        val result = assertNotNull(s.detector.run(library))
        assertEquals(5, result.read)
        assertEquals(4, result.intros)
        val episodes = s.store.libraryItems(library.id).filter { it.kind == ItemKind.EPISODE }
        val intros = s.store.libraryIntros(episodes.map { it.id })
        val (show, others) = episodes.partition { it.path!!.startsWith("Show/") }
        for (episode in show) {
            val intro = assertNotNull(intros.getValue(episode.id).intro, "episode ${episode.episode}")
            val start = (starts.getValue(episode.episode!!) * 1_000).toLong()
            assertNear(start, intro.startMs)
            assertNear(start + 20_000, intro.endMs)
        }
        assertNull(intros.getValue(others.single().id).intro)

        // The player reads it on the episode's page.
        val first = show.single { it.episode == 1 }
        val segment = s.service.catalog.item(first.id).segments.single()
        assertEquals(Wire.SegmentKind.INTRO, segment.kind)
        assertEquals(intros.getValue(first.id).intro, IntroSpan(segment.startMs, segment.endMs))

        // Nothing changed: no file is read again.
        s.reads.clear()
        assertEquals(0, s.detector.run(library)!!.read)
        assertEquals(emptyList(), s.reads)

        // A replaced file is read again, and until it is its old intro is not offered.
        val replaced = tv.resolve("Show/Season 01/Show - S01E01.mkv")
        episode(replaced, 500, 8.0, theme)
        s.service.scan(library.id)
        assertEquals(emptyList<MediaSegment>(), s.service.catalog.item(first.id).segments)
        assertEquals(1, s.detector.run(library)!!.read)
        assertEquals(listOf(replaced, replaced), s.reads, "its start, then its end")
        val again = s.service.catalog.item(first.id).segments.single()
        assertNear(8_000, again.startMs)
    }

    @Test fun `episodes with no theme in common get no intro`() = runBlocking {
        val s = setup()
        val tv = s.root.resolve("tv")
        for (number in 1..3) {
            episode(tv.resolve("Show/Season 01/Show - S01E0$number.mkv"), 10 * number, 5.0, tune(20 * number, 20.0))
        }
        val library = s.service.create("TV", LibraryKind.SHOWS, tv.toString())
        s.service.scan(library.id)
        val result = assertNotNull(s.detector.run(library))
        assertEquals(3, result.read)
        assertEquals(0, result.intros)
    }

    @Test fun `a file with no audio is noted once and not read again`() = runBlocking {
        val s = setup()
        val tv = s.root.resolve("tv")
        val silent = tv.resolve("Show/Season 01/Show - S01E01.mkv")
        Files.createDirectories(silent.parent)
        Files.writeString(silent, "not a video")
        val library = s.service.create("TV", LibraryKind.SHOWS, tv.toString())
        s.service.scan(library.id)
        assertEquals(IntroResult(read = 0, failed = 1, shows = 1, intros = 0), s.detector.run(library))
        assertEquals(IntroResult(read = 0, failed = 0, shows = 1, intros = 0), s.detector.run(library))
    }

    @Test fun `episodes that end on the same credits music get them as their credits`() = runBlocking {
        val credits = tune(7, 20.0)
        val lengths = mapOf(1 to 50.0, 2 to 58.0, 3 to 64.0, 4 to 70.0)
        // Where each episode's credits start, and how long it is, from its samples.
        val starts = HashMap<Int, Long>()
        val durations = HashMap<String, Long>()
        val pcms = lengths.mapValues { (number, before) ->
            val own = tune(200 + number, before)
            starts[number] = own.size * 1_000L / RATE
            (own + credits).also { durations["Show - S01E0$number.mkv"] = it.size * 1_000L / RATE }
        }
        val s = setup(tailShare = 0.5, durations = durations)
        val tv = s.root.resolve("tv")
        pcms.forEach { (number, pcm) -> encode(tv.resolve("Show/Season 01/Show - S01E0$number.mkv"), pcm) }
        val library = s.service.create("TV", LibraryKind.SHOWS, tv.toString())
        s.service.scan(library.id)

        val result = assertNotNull(s.detector.run(library))
        assertEquals(4, result.credits)
        assertEquals(0, result.intros)
        val episodes = s.store.libraryItems(library.id).filter { it.kind == ItemKind.EPISODE }
        val found = s.store.libraryIntros(episodes.map { it.id })
        for (episode in episodes) {
            val span = assertNotNull(found.getValue(episode.id).credits, "episode ${episode.episode}")
            assertNear(starts.getValue(episode.episode!!), span.startMs)
            assertEquals(
                durations.getValue("Show - S01E0${episode.episode}.mkv"),
                span.endMs,
                "credits that end with the episode run to its end",
            )
        }
        val segment = s.service.catalog.item(episodes.first().id).segments.single()
        assertEquals(Wire.SegmentKind.CREDITS, segment.kind)

        // Nothing changed: no file is read again.
        s.reads.clear()
        assertEquals(0, s.detector.run(library)!!.read)
        assertEquals(emptyList(), s.reads)

        // An episode read before credits detection has only its end read.
        val first = episodes.first()
        s.store.saveLibraryFingerprint(found.getValue(first.id).copy(tail = null, tailStartMs = null, credits = null))
        assertEquals(1, s.detector.run(library)!!.read)
        assertEquals(listOf(s.root.resolve("tv").resolve(first.path!!)), s.reads)
        assertTrue(
            s.store.libraryIntros(listOf(first.id)).getValue(first.id).credits != null,
            "its credits are found again",
        )
    }

    private companion object {
        const val RATE = 22_050
    }
}
