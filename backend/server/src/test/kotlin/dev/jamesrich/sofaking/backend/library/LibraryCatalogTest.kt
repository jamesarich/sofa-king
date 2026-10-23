package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveLibraryMetadata
import dev.jamesrich.sofaking.contract.UpdatePlayState
import dev.jamesrich.sofaking.contract.LetterCount
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryCatalogTest {
    private val media = MediaInfo(
        "matroska,webm", durationMs = 1_000_000, video = VideoStream("hevc", width = 3840, height = 2160, transfer = "smpte2084"),
        audio = listOf(AudioStream(1, "eac3", 6, "eng", default = true)), subtitles = listOf(SubtitleStream(2, "subrip", "eng", forced = true)),
    )
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)

    private fun Path.file(relative: String, text: String = "x"): Path =
        resolve(relative).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    private class Setup(val root: Path, val service: LibraryService, val clock: MutableClock, val store: BackendStore)

    private fun setup(http: HttpClient? = null): Setup = runBlocking {
        val dir = Files.createTempDirectory("sofa-catalog-")
        val root = Files.createDirectories(dir.resolve("media"))
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        val service = LibraryService(
            store, clock, listOf(MediaRoot("Media", root)), LibraryScanner({ media }), Jobs(clock),
            artwork = LibraryArtwork(http, dir.resolve("data/artwork"), "https://images.test/t/p"),
        )
        Setup(root, service, clock, store)
    }

    private suspend fun Setup.shows(): LibraryRecord {
        root.file("tv/Show (2020)/poster.jpg")
        root.file("tv/Show (2020)/Season 01/Show - S01E01 - Pilot.mkv")
        root.file("tv/Show (2020)/Season 01/Show - S01E02 - Second.mkv")
        root.file("tv/Show (2020)/Season 02/Show - S02E01 - Return.mkv")
        root.file("tv/Show (2020)/Specials/Show - S00E01 - Bonus.mkv")
        return service.create("TV", LibraryKind.SHOWS, root.resolve("tv").toString()).also { service.scan(it.id) }
    }

    @Test fun `a show lists its seasons in order, borrows its poster for them and offers the first episode`() = runBlocking<Unit> {
        val s = setup()
        val library = s.shows()
        val catalog = s.service.catalog
        val show = catalog.items(library.id, null, false, null, 100).items.single()
        assertEquals(Wire.ItemKind.SHOW, show.kind)
        assertEquals(3, show.children, "Specials, Season 1 and Season 2")
        assertEquals(4, show.unwatched)
        assertTrue(show.poster!!.startsWith("/artwork/${show.id}/poster?v="), show.poster)

        val detail = catalog.item(show.id)
        assertEquals(listOf(0, 1, 2), detail.children.map { it.season })
        assertEquals(show.poster, detail.children[1].poster, "a season without its own poster shows its show's")
        assertEquals("Pilot", detail.next?.title, "specials are not next")

        val season = catalog.item(detail.children[1].id)
        assertEquals(listOf("Pilot", "Second"), season.children.map { it.title })
        val pilot = catalog.item(season.children[0].id)
        assertEquals(show.id, pilot.item.showId)
        assertEquals("Show", pilot.item.showTitle)
        assertEquals(2160, pilot.item.videoHeight)
        assertTrue(pilot.item.hdr)
        assertEquals("eac3", pilot.audio.single().codec)
        assertTrue(pilot.subtitles.single().forced)
    }

    @Test fun `Recently added breaks a tie from one scan by the newest file`() = runBlocking<Unit> {
        val s = setup()
        val old = s.root.file("movies/Alpha (2001)/Alpha (2001).mkv")
        val new = s.root.file("movies/Zulu (2002)/Zulu (2002).mkv")
        val middle = s.root.file("movies/Mike (2003)/Mike (2003).mkv")
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000))
        Files.setLastModifiedTime(middle, java.nio.file.attribute.FileTime.fromMillis(1_500_000_000_000))
        Files.setLastModifiedTime(new, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000))
        val library = s.service.create("Movies", LibraryKind.MOVIES, s.root.resolve("movies").toString()).also { s.service.scan(it.id) }
        val catalog = s.service.catalog

        val shelf = catalog.home().shelves.single { it.id == Wire.ShelfId.RECENT }
        assertEquals(listOf("Zulu", "Mike", "Alpha"), shelf.items.map { it.title })
        assertEquals(listOf("Zulu", "Mike", "Alpha"), catalog.items(library.id, "added", false, null, 100).items.map { it.title })
        // Title order counts each first letter, on the first page only; other orders do not.
        val byTitle = catalog.items(library.id, "title", false, null, 1)
        assertEquals(listOf(LetterCount("A", 1), LetterCount("M", 1), LetterCount("Z", 1)), byTitle.letters)
        assertNull(catalog.items(library.id, "title", false, byTitle.next, 1).letters)
        assertNull(catalog.items(library.id, "added", false, null, 100).letters)
    }

    @Test fun `play state follows the 5 and 90 percent rules and feeds Continue watching`() = runBlocking<Unit> {
        val s = setup()
        val library = s.shows()
        val catalog = s.service.catalog
        val season = catalog.item(catalog.item(catalog.items(library.id, null, false, null, 10).items.single().id).children[1].id)
        val (pilot, second) = season.children

        assertNull(catalog.updatePlayState(pilot.id, UpdatePlayState(positionMs = 20_000)).playState, "under 5% is not a start")
        assertEquals(500_000, catalog.updatePlayState(pilot.id, UpdatePlayState(positionMs = 500_000)).playState?.positionMs)
        assertEquals(pilot.id, catalog.home().shelves.first { it.id == Wire.ShelfId.CONTINUE }.items.single().id)

        s.clock.now = s.clock.now.plus(Duration.ofMinutes(1))
        val done = catalog.updatePlayState(pilot.id, UpdatePlayState(positionMs = 950_000)).playState!!
        assertTrue(done.watched)
        assertEquals(0, done.positionMs)
        assertEquals(second.id, catalog.home().shelves.first { it.id == Wire.ShelfId.CONTINUE }.items.single().id, "the next episode is up")

        // A rewatch stopped part way keeps the tick and resumes, from the shelf and the show.
        s.clock.now = s.clock.now.plus(Duration.ofMinutes(1))
        val rewatch = catalog.updatePlayState(pilot.id, UpdatePlayState(positionMs = 400_000)).playState!!
        assertTrue(rewatch.watched)
        assertEquals(400_000, rewatch.positionMs)
        assertEquals(pilot.id, catalog.home().shelves.first { it.id == Wire.ShelfId.CONTINUE }.items.single().id, "the rewatch is up")
        assertEquals(pilot.id, catalog.item(catalog.items(library.id, null, false, null, 10).items.single().id).next?.id, "and the show resumes it")

        // Marking the show watched marks every episode; unwatched clears them again.
        val show = catalog.items(library.id, null, false, null, 10).items.single()
        assertEquals(0, catalog.updatePlayState(show.id, UpdatePlayState(watched = true)).unwatched)
        assertTrue(catalog.home().shelves.none { it.id == Wire.ShelfId.CONTINUE })
        assertEquals(0, catalog.items(library.id, null, true, null, 10).total, "the unwatched filter drops a watched show")
        assertEquals(4, catalog.updatePlayState(show.id, UpdatePlayState(watched = false)).unwatched)
        assertFailsWith<ApiFailure> { catalog.updatePlayState(show.id, UpdatePlayState(positionMs = 5)) }
    }

    @Test fun `TMDB fills what files leave out, but a nfo and local art win`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val s = setup(HttpClient(MockEngine { request ->
            calls += request.url.encodedPath
            respond(jpeg, HttpStatusCode.OK)
        }))
        val movies = s.root.resolve("movies")
        movies.file("Alien (1979)/Alien (1979).mkv")
        movies.file("Heat (1995)/Heat (1995).mkv")
        movies.file("Heat (1995)/Heat (1995).nfo", "<movie><title>Heat</title><genre>Crime</genre><plot>A heist.</plot></movie>")
        Files.write(movies.resolve("Heat (1995)/poster.jpg"), jpeg)
        val library = s.service.create("Movies", LibraryKind.MOVIES, movies.toString())
        s.service.scan(library.id)
        val items = s.store.libraryItems(library.id).associateBy { it.title }
        s.store.saveLibraryMetadata(listOf(
            MetadataRecord(items.getValue("Alien").id, 348, "2026-01-01T00:00:00Z", MetadataFacts(title = "Alien", overview = "In space.", genres = listOf("Horror"), rating = 8.1, poster = "/a.jpg", backdrop = "/b.jpg")),
            MetadataRecord(items.getValue("Heat").id, 949, "2026-01-01T00:00:00Z", MetadataFacts(title = "Heat (TMDB)", overview = "TMDB plot.", genres = listOf("Drama"), poster = "/h.jpg")),
        ))
        s.service.scan(library.id) // a scan bumps the revision the catalog rebuilds on

        val page = s.service.catalog.items(library.id, "rating", false, null, 10).items
        val alien = page.first { it.title == "Alien" }
        assertEquals("In space.", alien.overview)
        assertEquals(listOf("Horror"), alien.genres)
        val heat = page.first { it.title == "Heat" }
        assertEquals("A heist.", heat.overview, "the nfo wins")
        assertEquals(listOf("Crime"), heat.genres)
        assertEquals(alien, page.first(), "rated items sort first")

        val view = s.service.catalog.snapshot().views
        assertEquals(ArtSource.Tmdb("/a.jpg", "w342"), view.getValue(alien.id).poster)
        assertIs<ArtSource.Local>(view.getValue(heat.id).poster, "local art wins")
        val artwork = s.service.artwork!!
        assertNotNull(artwork.image(view.getValue(alien.id).poster!!))
        assertNotNull(artwork.image(view.getValue(alien.id).poster!!))
        assertEquals(listOf("/t/p/w342/a.jpg"), calls, "a TMDB image is downloaded once")
        assertNull(artwork.image(ArtSource.Tmdb("/../etc.jpg", "w342")))
        assertNull(artwork.image(ArtSource.Local(movies, "../outside.jpg")))
    }

    @Test fun `search finds titles, top-level items before episodes`() = runBlocking<Unit> {
        val s = setup()
        s.shows()
        val found = s.service.catalog.search("re", 10).items
        assertEquals(listOf("Return"), found.map { it.title })
        assertEquals(listOf(Wire.ItemKind.SHOW), s.service.catalog.search("show", 10).items.map { it.kind })
    }

    @Test fun `search finds movies and shows by cast and director after title matches`() = runBlocking<Unit> {
        val s = setup()
        val movies = s.root.resolve("movies")
        movies.file("Alien (1979)/Alien (1979).mkv")
        movies.file("Weaver Story (2001)/Weaver Story (2001).mkv")
        movies.file("Heat (1995)/Heat (1995).mkv")
        val library = s.service.create("Movies", LibraryKind.MOVIES, movies.toString())
        s.service.scan(library.id)
        val items = s.store.libraryItems(library.id).associateBy { it.title }
        s.store.saveLibraryMetadata(listOf(
            MetadataRecord(items.getValue("Alien").id, 348, "2026-01-01T00:00:00Z", MetadataFacts(title = "Alien", cast = listOf("Sigourney Weaver"), directors = listOf("Ridley Scott"))),
            MetadataRecord(items.getValue("Heat").id, 949, "2026-01-01T00:00:00Z", MetadataFacts(title = "Heat", cast = listOf("Al Pacino"), directors = listOf("Michael Mann"))),
        ))
        s.service.scan(library.id)

        assertEquals(listOf("Weaver Story", "Alien"), s.service.catalog.search("weaver", 10).items.map { it.title })
        assertEquals(listOf("Heat"), s.service.catalog.search("michael mann", 10).items.map { it.title })
    }
}
