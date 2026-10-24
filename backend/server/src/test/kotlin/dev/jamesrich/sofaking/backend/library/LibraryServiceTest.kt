package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryServiceTest {
    private val media =
        MediaInfo("matroska,webm", durationMs = 60_000, video = VideoStream("h264", width = 1920, height = 1080))

    private fun Path.file(relative: String, text: String = "x"): Path = resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    private class Setup(
        val root: Path,
        val service: LibraryService,
        val probes: AtomicInteger,
        val clock: MutableClock,
        val store: dev.jamesrich.sofaking.backend.store.BackendStore,
    )

    private fun setup(): Setup = runBlocking {
        val dir = Files.createTempDirectory("sofa-library-")
        val root = Files.createDirectories(dir.resolve("media"))
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        val probes = AtomicInteger()
        val scanner = LibraryScanner({
            probes.incrementAndGet()
            media
        }, missingGrace = Duration.ofDays(7))
        Setup(
            root,
            LibraryService(store, clock, listOf(MediaRoot("Media", root)), scanner, Jobs(clock)),
            probes,
            clock,
            store,
        )
    }

    @Test fun `a movie library reads folder names, nfo and art, and probes only new or changed files`() = runBlocking {
        val s = setup()
        val movies = Files.createDirectories(s.root.resolve("movies"))
        movies.file("Alien (1979)/Alien (1979).mkv")
        movies.file("Alien (1979)/poster.jpg")
        movies.file("Alien (1979)/Alien (1979)-trailer.mkv")
        movies.file("Heat (1995) [1080p]/Heat.1995.mkv")
        movies.file(
            "Heat (1995) [1080p]/Heat.1995.nfo",
            "<movie><title>Heat</title><year>1995</year><plot>A heist.</plot></movie>",
        )
        movies.file("Alien (1979)/Featurettes/Making Alien.mkv")
        movies.file("Heat (1995) [1080p]/Samples/Heat-sample.mkv")
        // Extras of a folder of loose movies belong to none of them.
        movies.file("Extras/Something.mkv")
        val library = s.service.create("Movies", LibraryKind.MOVIES, movies.toString())

        val first = s.service.scan(library.id)
        assertEquals(4, first.files)
        assertEquals(4, first.added)
        assertEquals(4, s.probes.get())
        val items = s.store.libraryItems(library.id).associateBy { it.title }
        assertEquals(setOf("Alien", "Heat", "Trailer", "Making Alien"), items.keys)
        val alien = items.getValue("Alien")
        assertEquals(
            listOf(ItemKind.EXTRA, ItemKind.EXTRA),
            listOf(items.getValue("Trailer").kind, items.getValue("Making Alien").kind),
        )
        assertEquals(
            listOf(alien.id, alien.id),
            listOf(items.getValue("Trailer").parentId, items.getValue("Making Alien").parentId),
        )
        assertEquals(
            listOf(ExtraKind.TRAILER.wire, ExtraKind.FEATURETTE.wire),
            listOf(items.getValue("Trailer").details?.extra, items.getValue("Making Alien").details?.extra),
        )
        assertEquals(1979, items.getValue("Alien").year)
        assertEquals("Alien (1979)/poster.jpg", items.getValue("Alien").poster)
        assertEquals("A heist.", items.getValue("Heat").overview)
        assertEquals(60_000, items.getValue("Heat").durationMs)

        // An unchanged file keeps its probe; ids are the same on every scan.
        val again = s.service.scan(library.id)
        assertEquals(0, again.added + again.updated)
        assertEquals(4, s.probes.get())
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
        tv.file("Show (2020)/Extras/Cast interview.mkv")
        tv.file("Show (2020)/Season 01/Deleted Scenes/Cut scene.mkv")
        val library = s.service.create("TV", LibraryKind.SHOWS, tv.toString())
        val result = s.service.scan(library.id)
        assertEquals(6, result.files)

        val items = s.store.libraryItems(library.id)
        val shows = items.filter { it.kind == ItemKind.SHOW }.associateBy { it.title }
        assertEquals(setOf("The Show", "Nightly News"), shows.keys)
        val seasons = items.filter {
            it.kind == ItemKind.SEASON && it.parentId == shows.getValue("The Show").id
        }.map { it.title }.toSet()
        assertEquals(setOf("Season 1", "Specials"), seasons)
        val pilot = items.single { it.kind == ItemKind.EPISODE && it.title == "Pilot" }
        assertEquals(1, pilot.season)
        assertEquals(1, pilot.episode)
        val news = items.single { it.kind == ItemKind.EPISODE && it.aired == "2026-10-17" }
        assertEquals("Evening", news.title)
        assertEquals(2026, news.season)
        // A show's extras, from its folder or a season's, belong to the show and are no season.
        val extras = items.filter { it.kind == ItemKind.EXTRA }
        assertEquals(setOf("Cast interview", "Cut scene"), extras.map { it.title }.toSet())
        assertEquals(setOf(shows.getValue("The Show").id), extras.map { it.parentId }.toSet())
        assertEquals(
            setOf(ExtraKind.OTHER.wire, ExtraKind.DELETED_SCENE.wire),
            extras.map {
                it.details?.extra
            }.toSet(),
        )
    }

    @Test
    fun `a missing file keeps its row for the grace period, and an emptied folder changes nothing`() = runBlocking {
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

    @Test
    fun `folders right under the media directory named for what they hold are suggested until a library takes them`() =
        runBlocking {
            val s = setup()
            val movies = Files.createDirectories(s.root.resolve("Movies"))
            val shows = Files.createDirectories(s.root.resolve("TV_Shows"))
            Files.createDirectories(s.root.resolve("Films/Movies"))
            Files.createDirectories(s.root.resolve("Music"))
            val suggested = s.service.folders.suggestions()
            assertEquals(
                listOf(
                    LibrarySuggestion("Films", LibraryKind.MOVIES, s.root.resolve("Films")),
                    LibrarySuggestion("Movies", LibraryKind.MOVIES, movies),
                    LibrarySuggestion("TV_Shows", LibraryKind.SHOWS, shows),
                ),
                suggested.map { it.copy(path = it.path.normalize()) },
                "only names that say what a folder holds, and only one level down",
            )
            s.service.create("Movies", LibraryKind.MOVIES, movies.toString())
            assertEquals(listOf("Films", "TV_Shows"), s.service.folders.suggestions().map { it.name })
        }

    @Test fun `a relative media root drops the folders in use, and two adds of one folder give one library`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-library-")
            val absolute = Files.createDirectories(dir.resolve("media/Movies")).parent
            val relative = Path.of("").toAbsolutePath().relativize(absolute)
            val clock = MutableClock()
            val store = openStore(dir.resolve("data"), clock)
            val service =
                LibraryService(
                    store,
                    clock,
                    listOf(MediaRoot("Media", relative)),
                    LibraryScanner({
                        media
                    }),
                    Jobs(clock),
                )
            val folder = relative.resolve("Movies").toString()
            val outcomes = kotlinx.coroutines.coroutineScope {
                List(2) {
                    async(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching { service.create("Movies", LibraryKind.MOVIES, folder) }
                    }
                }.map { it.await() }
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(
                "LIBRARY_EXISTS",
                (
                    outcomes.single {
                        it.isFailure
                    }.exceptionOrNull() as ApiFailure
                    ).code,
                "a double submit is a 409, not a 500",
            )
            assertTrue(
                service.folders.choices().none {
                    it.fileName.toString() == "Movies"
                },
                "the folder in use is not offered",
            )
            assertTrue(service.folders.suggestions().isEmpty())
            store.close()
            dir.toFile().deleteRecursively()
        }

    @Test fun `a library folder must sit inside the media directory and be unused`() = runBlocking {
        val s = setup()
        val outside = Files.createTempDirectory("sofa-outside-")
        assertEquals(
            HttpStatusCode.BadRequest,
            assertFailsWith<ApiFailure> {
                s.service.create("Out", LibraryKind.MOVIES, outside.toString())
            }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            assertFailsWith<ApiFailure> {
                s.service.create("Up", LibraryKind.MOVIES, s.root.resolve("../").toString())
            }.status,
        )
        val movies = Files.createDirectories(s.root.resolve("movies"))
        Files.createSymbolicLink(s.root.resolve("escape"), outside)
        assertEquals(
            HttpStatusCode.BadRequest,
            assertFailsWith<ApiFailure> {
                s.service.create("Link", LibraryKind.MOVIES, s.root.resolve("escape").toString())
            }.status,
        )
        s.service.create("Movies", LibraryKind.MOVIES, movies.toString())
        assertEquals(
            HttpStatusCode.Conflict,
            assertFailsWith<ApiFailure> {
                s.service.create("Again", LibraryKind.MOVIES, movies.toString())
            }.status,
        )
        assertFalse(s.service.folders.choices().contains(movies))
        assertTrue(s.service.folders.choices().contains(s.root))
    }

    @Test fun `removing a library does not wait for its files to be probed, and the scan then writes nothing`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-library-")
            val root = Files.createDirectories(dir.resolve("media"))
            val clock = MutableClock()
            val store = openStore(dir.resolve("data"), clock)
            val probing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scanner =
                LibraryScanner({
                    probing.complete(Unit)
                    release.await()
                    media
                }, missingGrace = Duration.ofDays(7))
            val service = LibraryService(store, clock, listOf(MediaRoot("Media", root)), scanner, Jobs(clock))
            val movies = Files.createDirectories(root.resolve("movies"))
            movies.file("Alien (1979)/Alien (1979).mkv")
            val library = service.create("Movies", LibraryKind.MOVIES, movies.toString())

            val scan = async(Dispatchers.Default) { runCatching { service.scan(library.id) } }
            probing.await()
            withTimeout(5_000) { service.delete(library.id) }
            release.complete(Unit)

            assertEquals("LIBRARY_NOT_FOUND", (scan.await().exceptionOrNull() as ApiFailure).code)
            assertTrue(store.libraryItems(library.id).isEmpty())
            store.close()
            dir.toFile().deleteRecursively()
        }
}
