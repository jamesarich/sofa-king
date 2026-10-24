package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.libraryScrub
import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrubSheetsTest {
    private class Setup(
        val dir: Path,
        val root: Path,
        val store: BackendStore,
        val service: LibraryService,
        val scrub: ScrubSheets,
        val jobs: Jobs,
    )

    /** Movies the scan takes for ten minutes long unless [durations] knows a file's length. */
    private fun setup(maker: SheetMaker, durations: Map<String, Long> = emptyMap()): Setup = runBlocking {
        val dir = Files.createTempDirectory("sofa-scrub-")
        val root = Files.createDirectories(dir.resolve("media"))
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        val jobs = Jobs(clock)
        val scrub = ScrubSheets(store, clock, jobs, maker, dir.resolve("data/scrub"))
        val scanner = LibraryScanner({
            MediaInfo(
                "matroska,webm",
                durationMs =
                durations[it.fileName.toString()] ?: 600_000,
            )
        }, missingGrace = Duration.ofDays(7))
        Setup(
            dir,
            root,
            store,
            LibraryService(store, clock, listOf(MediaRoot("Media", root)), scanner, jobs, scrub = scrub),
            scrub,
            jobs,
        )
    }

    private fun movie(root: Path, name: String, body: String = "x"): Path {
        val folder = Files.createDirectories(root.resolve("movies/$name"))
        return folder.resolve("$name.mkv").also { Files.writeString(it, body) }
    }

    /** A maker that writes one byte per sheet and remembers which files it read; [fails] names files it cannot read. */
    private class FakeMaker(val fails: Set<String> = emptySet()) : SheetMaker {
        val reads = ArrayList<String>()
        override suspend fun make(file: Path, into: Path): SheetSize? {
            reads += file.fileName.toString()
            if (file.fileName.toString() in fails) return null
            repeat(2) { Files.write(into.resolve("$it.jpg"), byteArrayOf(it.toByte())) }
            return SheetSize(240, 136, 2)
        }
    }

    @Test fun `long files get sheets once, a replaced file again, and a removed library's sheets go`() = runBlocking {
        val maker = FakeMaker(fails = setOf("Broken (2003).mkv"))
        val s = setup(maker, durations = mapOf("Short (2002).mkv" to 120_000))
        movie(s.root, "Alpha (2001)")
        movie(s.root, "Short (2002)")
        movie(s.root, "Broken (2003)")
        val library = s.service.create("Movies", LibraryKind.MOVIES, s.root.resolve("movies").toString())
        s.service.scan(library.id)

        assertEquals(1 to 1, s.scrub.run(library))
        assertEquals(
            listOf("Alpha (2001).mkv", "Broken (2003).mkv"),
            maker.reads.sorted(),
            "a file under five minutes gets none",
        )
        val items = s.store.libraryItems(library.id).associateBy { it.path!!.substringBefore('/') }
        val alpha = items.getValue("Alpha (2001)")
        val made = assertNotNull(s.store.libraryScrub(alpha.id))
        assertEquals(listOf(2, 60, 240, 136), listOf(made.sheets, made.frames, made.tileWidth, made.tileHeight))
        assertNotNull(s.scrub.sheet(alpha.id, 1))
        assertNull(s.scrub.sheet(alpha.id, 2))
        assertNull(s.scrub.sheet("../data", 0))
        assertEquals(
            0,
            assertNotNull(s.store.libraryScrub(items.getValue("Broken (2003)").id)).sheets,
            "a failure is noted",
        )
        assertEquals("Movies: 1 files, 1 without video", s.jobs.snapshot().single { it.id == SCRUB_JOB }.lastNote)

        // The episode's page offers them.
        val offered = assertNotNull(s.service.catalog.item(alpha.id).scrub)
        assertEquals(
            listOf("/artwork/${alpha.id}/scrub/0", "/artwork/${alpha.id}/scrub/1"),
            offered.sheets.map {
                it.substringBefore('?')
            },
        )
        assertNull(s.service.catalog.item(items.getValue("Broken (2003)").id).scrub)

        // Nothing changed: no file is read again, the failed one included.
        maker.reads.clear()
        assertEquals(0 to 0, s.scrub.run(library))
        assertEquals(emptyList(), maker.reads)

        // Sheets deleted by hand are made again.
        s.dir.resolve("data/scrub/${alpha.id}").toFile().deleteRecursively()
        assertEquals(1 to 0, s.scrub.run(library))
        assertEquals(listOf("Alpha (2001).mkv"), maker.reads)
        maker.reads.clear()

        // A replaced file is read again, and until it is its old sheets are not offered.
        movie(s.root, "Alpha (2001)", body = "replaced")
        Files.setLastModifiedTime(
            s.root.resolve("movies/Alpha (2001)/Alpha (2001).mkv"),
            java.nio.file.attribute.FileTime.fromMillis(1_000),
        )
        s.service.scan(library.id)
        assertNull(s.service.catalog.item(alpha.id).scrub)
        assertEquals(1 to 0, s.scrub.run(library))
        assertEquals(listOf("Alpha (2001).mkv"), maker.reads)
        assertNotNull(s.service.catalog.item(alpha.id).scrub)

        // A removed library's sheets go with the next pass.
        s.service.delete(library.id)
        s.scrub.run(
            LibraryRecord(
                "other",
                "Other",
                LibraryKind.MOVIES,
                s.root.resolve("movies").toString(),
                "2026-10-10T13:00:00Z",
            ),
        )
        assertNull(s.store.libraryScrub(alpha.id))
        assertFalse(Files.exists(s.dir.resolve("data/scrub/${alpha.id}")))
    }

    @Test fun `FFmpeg tiles one key frame every ten seconds onto a sheet`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-scrub-ffmpeg-")
        val clip = dir.resolve("clip.mkv")
        // Two and a half minutes of test pattern at a key frame a second, small and quick to make.
        val exit = ProcessBuilder(
            "ffmpeg", "-nostdin", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=320x180:rate=5:duration=150",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "5", clip.toString(),
        ).inheritIO().start().waitFor()
        assertEquals(0, exit, "ffmpeg makes the test clip")
        val into = Files.createDirectories(dir.resolve("out"))

        val size = assertNotNull(FfmpegSheetMaker("ffmpeg").make(clip, into))
        assertEquals(SheetSize(240, 136, 1), size, "one sheet holds the 15 tiles, 240 by 135 rounded up to even")
        val sheet = ImageIO.read(into.resolve("0.jpg").toFile())
        assertEquals(240 * SCRUB_COLUMNS, sheet.width)
        assertTrue(Files.notExists(into.resolve("1.jpg")))

        val text = dir.resolve("not-a-video.mkv").also { Files.writeString(it, "x") }
        assertNull(FfmpegSheetMaker("ffmpeg").make(text, Files.createDirectories(dir.resolve("none"))))
        dir.toFile().deleteRecursively()
    }

    @Test fun `each tile is the key frame at its mark, not half an interval later`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-scrub-marks-")
        val clip = dir.resolve("ramp.mkv")
        // Half a minute whose gray brightens 7 luma steps a second from black, with a key frame every 2 seconds.
        val exit = ProcessBuilder(
            "ffmpeg", "-nostdin", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "nullsrc=size=320x180:rate=10:duration=30,geq=lum='16+T*7':cb=128:cr=128",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "20", "-pix_fmt", "yuv420p", clip.toString(),
        ).inheritIO().start().waitFor()
        assertEquals(0, exit, "ffmpeg makes the test clip")
        val into = Files.createDirectories(dir.resolve("out"))

        val size = assertNotNull(FfmpegSheetMaker("ffmpeg").make(clip, into))
        val sheet = ImageIO.read(into.resolve("0.jpg").toFile())
        val grays = (0 until 3).map { tile ->
            sheet.getRGB(tile * size.tileWidth + size.tileWidth / 2, size.tileHeight / 2) and 0xff
        }
        // Luma 16 + 70 a tile, shown full range: about 0, 81 and 163; the frames 5 seconds on would be about 40 more.
        grays.forEachIndexed { tile, gray ->
            assertTrue(abs(gray - tile * 81) <= 15, "tile $tile shows ${tile * 10} s, read gray $gray")
        }
        dir.toFile().deleteRecursively()
    }
}
