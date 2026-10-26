package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicCatalogTest {
    private class Setup(val service: LibraryService, val library: LibraryRecord)

    /** Band's two albums (Later with a cover beside it, Early with one inside its first track) and a compilation. */
    private fun setup(): Setup = runBlocking {
        val dir = Files.createTempDirectory("sofa-music-catalog-")
        val music = Files.createDirectories(dir.resolve("media/Music"))
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        val tags = mapOf(
            "Band/Early/01 One.flac" to AudioTags("One", "Band", album = "Early", track = 1, date = "1990"),
            "Band/Early/02 Two.flac" to AudioTags("Two", "Band", album = "Early", track = 2, date = "1990"),
            "Band/Later/01 Three.flac" to AudioTags("Three", "Band", album = "Later", track = 1, date = "2001"),
            "Mix/01 Duet.flac" to AudioTags("Duet", "Guest", "Various Artists", "Mix", 1, genres = listOf("Pop")),
        )
        for (path in tags.keys) {
            Files.createDirectories(music.resolve(path).parent)
            Files.writeString(music.resolve(path), "x")
        }
        Files.writeString(music.resolve("Band/Later/cover.jpg"), "x")
        val scanner = LibraryScanner({ file ->
            val relative = music.relativize(file).joinToString("/")
            MediaInfo(
                "flac",
                durationMs = 180_000,
                audio = listOf(AudioStream(0, "flac")),
                tags = tags[relative],
                picture = relative == "Band/Early/01 One.flac",
            )
        })
        val service =
            LibraryService(store, clock, listOf(MediaRoot("Media", dir.resolve("media"))), scanner, Jobs(clock))
        val library = service.create("Music", LibraryKind.MUSIC, music.toString())
        service.scan(library.id)
        Setup(service, library)
    }

    @Test fun `a client that does not show music never sees a Music library or its items`() = runBlocking<Unit> {
        val s = setup()
        val catalog = s.service.catalog
        assertTrue(catalog.libraries().isEmpty())
        assertTrue(catalog.home().shelves.isEmpty())
        assertEquals(0, catalog.search("o", 50).total)
        assertFailsWith<ApiFailure> { catalog.items(s.library.id, null, false, null, 100) }
        val artist = catalog.items(s.library.id, null, false, null, 100, music = true).items.first()
        assertFailsWith<ApiFailure> { catalog.item(artist.id) }
    }

    @Test fun `artists lead to their albums, newest first, and albums to their tracks in order`() = runBlocking<Unit> {
        val s = setup()
        val catalog = s.service.catalog
        assertEquals(Wire.LibraryKind.MUSIC, catalog.libraries(music = true).single().kind)
        val artists = catalog.items(s.library.id, null, false, null, 100, music = true).items
        assertEquals(listOf("Band", "Various Artists"), artists.map { it.title })
        assertTrue(artists.all { it.kind == Wire.ItemKind.ARTIST })

        val band = catalog.item(artists[0].id, music = true)
        assertEquals(listOf("Later", "Early"), band.children.map { it.title })
        val later = band.children[0]
        assertEquals(band.item.id, later.artistId)
        assertEquals("Band", later.artist)
        assertEquals(2001, later.year)
        // The artist has no picture of its own, so it shows its newest album's cover.
        assertEquals(later.poster, band.item.poster)

        val early = catalog.item(band.children[1].id, music = true)
        assertEquals(listOf("One", "Two"), early.children.map { it.title })
        val one = early.children[0]
        assertEquals("Early", one.album)
        assertEquals(early.item.id, one.parentId)
        assertEquals(1, one.track)
        assertNull(one.trackArtist)
        // Early's cover is the picture inside its first track, served from the album for every track.
        assertNotNull(early.item.poster)
        assertEquals(early.item.poster, one.poster)
        assertTrue(one.poster!!.startsWith("/artwork/${early.item.id}/poster"), one.poster)

        val duet = catalog.search("duet", 50, music = true).items.single()
        assertEquals("Guest", duet.trackArtist)
        assertEquals("Various Artists", duet.artist)
    }

    @Test fun `a Music library lists its albums on request and in Recently added`() = runBlocking<Unit> {
        val s = setup()
        val catalog = s.service.catalog
        val albums = catalog.items(s.library.id, null, false, null, 100, music = true, kind = "album").items
        assertEquals(listOf("Early", "Later", "Mix"), albums.map { it.title })
        assertEquals(
            listOf("Pop"),
            catalog.items(s.library.id, null, false, null, 100, music = true, kind = "album").genres,
        )
        val recent = catalog.home(music = true).shelves.single { it.libraryId == s.library.id }
        assertTrue(recent.items.all { it.kind == Wire.ItemKind.ALBUM })
        assertFailsWith<ApiFailure> {
            catalog.items(s.library.id, null, false, null, 100, music = true, kind = "track")
        }
    }
}
