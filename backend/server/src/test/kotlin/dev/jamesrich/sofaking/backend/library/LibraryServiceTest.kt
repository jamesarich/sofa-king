package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.openStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LibraryServiceTest {
    private val media = MediaInfo("matroska,webm", durationMs = 60_000, video = VideoStream("h264", width = 1920, height = 1080))

    private fun Path.file(relative: String, text: String = "x"): Path =
        resolve(relative).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    private class Setup(val root: Path, val service: LibraryService, val probes: AtomicInteger, val clock: MutableClock, val store: dev.jamesrich.sofaking.backend.store.BackendStore)

    private fun setup(): Setup = runBlocking {
        val dir = Files.createTempDirectory("sofa-library-")
        val root = Files.createDirectories(dir.resolve("media"))
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        val probes = AtomicInteger()
        val scanner = LibraryScanner({ probes.incrementAndGet(); media }, missingGrace = Duration.ofDays(7))
        Setup(root, LibraryService(store, clock, listOf(MediaRoot("Media", root)), scanner, Jobs(clock)), probes, clock, store)
    }

    @Test fun `a movie library reads folder names, nfo and art, and probes only new or changed files`() = runBlocking {
        val s = setup()
        val movies = Files.createDirectories(s.root.resolve("movies"))
        movies.file("Alien (1979)/Alien (1979).mkv")
        movies.file("Alien (1979)/poster.jpg")
        movies.file("Alien (1979)/Alien (1979)-trailer.mkv")
        movies.file("Heat (1995) [1080p]/Heat.1995.mkv")
        movies.file("Heat (1995) [1080p]/Heat.1995.nfo", "<movie><title>Heat</title><year>1995</year><plot>A heist.</plot></movie>")
        movies.file("Extras/Something.mkv")
        val library = s.service.create("Movies", LibraryKind.MOVIES, movies.toString())

        val first = s.service.scan(library.id)
        assertEquals(2, first.files)
        assertEquals(2, first.added)
        assertEquals(2, s.probes.get())
        val items = s.store.libraryItems(library.id).associateBy { it.title }
        assertEquals(1979, items.getValue("Alien").year)
        assertEquals("Alien (1979)/poster.jpg", items.getValue("Alien").poster)
        assertEquals("A heist.", items.getValue("Heat").overview)
        assertEquals(60_000, items.getValue("Heat").durationMs)

        // An unchanged file keeps its probe; ids are the same on every scan.
        val again = s.service.scan(library.id)
        assertEquals(0, again.added + again.updated)
        assertEquals(2, s.probes.get())
        assertEquals(items.values.map { it.id }.toSet(), s.store.libraryItems(library.id).map { it.id }.toSet())
    }

    @Test fun `a show library builds shows, seasons and episodes, including date-based recordings`() = runBlocking {
        val s = setup()
        val tv = Files.createDirectories(s.root.resolve("tv"))
        tv.file("Show (2020)/tvshow.nfo", "<tvshow><title>The Show</title><year>2020</year></tvshow>")
        tv.file("Show (2020)/Season 01/Show - S01E01 - Pilot.mkv")
        tv.file("Show (2020)/Season 01/Show - S01E02.mkv")
        tv.file("Show (2020)/Specials/Show - S00E01 - Making of.mkv")
        tv.file("Nightly News/Season 2026/Nightly News - 2026-10-17 - Evening.ts")
        tv.file(".inprogress/Nightly News/Season 2026/Nightly News - 2026-10-18 - Evening.ts")
        val library = s.service.create("TV", LibraryKind.SHOWS, tv.toString())
        val result = s.service.scan(library.id)
        assertEquals(4, result.files)

        val items = s.store.libraryItems(library.id)
        val shows = items.filter { it.kind == ItemKind.SHOW }.associateBy { it.title }
        assertEquals(setOf("The Show", "Nightly News"), shows.keys)
        val seasons = items.filter { it.kind == ItemKind.SEASON && it.parentId == shows.getValue("The Show").id }.map { it.title }.toSet()
        assertEquals(setOf("Season 1", "Specials"), seasons)
        val pilot = items.single { it.kind == ItemKind.EPISODE && it.title == "Pilot" }
        assertEquals(1, pilot.season)
        assertEquals(1, pilot.episode)
        val news = items.single { it.kind == ItemKind.EPISODE && it.aired == "2026-10-17" }
        assertEquals("Evening", news.title)
        assertEquals(2026, news.season)
    }

    @Test fun `a missing file keeps its row for the grace period, and an emptied folder changes nothing`() = runBlocking {
        val s = setup()
        val videos = Files.createDirectories(s.root.resolve("videos"))
        val kept = videos.file("Birthday.mp4")
        val gone = videos.file("Beach.mp4")
        val library = s.service.create("Home", LibraryKind.VIDEOS, videos.toString())
        s.service.scan(library.id)
        Files.delete(gone)

        assertEquals(1, s.service.scan(library.id).missing)
        assertNotNull(s.store.libraryItems(library.id).single { it.title == "Beach" }.missingSince)
        s.clock.now = s.clock.now.plus(Duration.ofDays(8))
        assertEquals(1, s.service.scan(library.id).removed)
        assertEquals(listOf("Birthday"), s.store.libraryItems(library.id).map { it.title })

        // An unmounted disk reads as an empty folder: the scan says so and keeps every row.
        Files.delete(kept)
        val unmounted = s.service.scan(library.id)
        assertTrue(unmounted.unavailable)
        assertNull(s.store.libraryItems(library.id).single().missingSince)
        assertEquals(false, s.service.library(library.id).scanOk)
    }

    @Test fun `a library folder must sit inside the media directory and be unused`() = runBlocking {
        val s = setup()
        val outside = Files.createTempDirectory("sofa-outside-")
        assertEquals(400, assertFailsWith<ApiFailure> { s.service.create("Out", LibraryKind.MOVIES, outside.toString()) }.status)
        assertEquals(400, assertFailsWith<ApiFailure> { s.service.create("Up", LibraryKind.MOVIES, s.root.resolve("../").toString()) }.status)
        val movies = Files.createDirectories(s.root.resolve("movies"))
        Files.createSymbolicLink(s.root.resolve("escape"), outside)
        assertEquals(400, assertFailsWith<ApiFailure> { s.service.create("Link", LibraryKind.MOVIES, s.root.resolve("escape").toString()) }.status)
        s.service.create("Movies", LibraryKind.MOVIES, movies.toString())
        assertEquals(409, assertFailsWith<ApiFailure> { s.service.create("Again", LibraryKind.MOVIES, movies.toString()) }.status)
        assertFalse(s.service.folderChoices().contains(movies))
        assertTrue(s.service.folderChoices().contains(s.root))
    }
}
