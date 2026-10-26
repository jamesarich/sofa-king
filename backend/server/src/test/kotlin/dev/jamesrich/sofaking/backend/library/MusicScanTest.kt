package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicScanTest {
    private class Scanned(
        val files: Int,
        val items: List<LibraryItemRecord>,
        val rescanned: List<LibraryItemRecord>,
        val listed: Boolean,
    ) {
        val byTitle = items.associateBy { it.title }

        fun tracksOf(album: String) =
            items.filter { it.parentId == byTitle.getValue(album).id }.sortedBy { it.sortTitle }.map { it.title }
    }

    /** Scans a Music library of [tags]' files, each probed as those tags, plus the [others] files. */
    private fun scan(tags: Map<String, AudioTags?>, vararg others: String): Scanned = runBlocking {
        val dir = Files.createTempDirectory("sofa-music-")
        val music = Files.createDirectories(dir.resolve("media/Music"))
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        for (path in tags.keys + others) music.file(path)
        val scanner = LibraryScanner({ file ->
            MediaInfo(
                "flac",
                durationMs = 200_000,
                audio = listOf(AudioStream(0, "flac")),
                tags = tags[music.relativize(file).joinToString("/")],
            )
        })
        val service =
            LibraryService(store, clock, listOf(MediaRoot("Media", dir.resolve("media"))), scanner, Jobs(clock))
        val library = service.create("Music", LibraryKind.MUSIC, music.toString())
        val files = service.scan(library.id).files
        val items = store.libraryItems(library.id)
        service.scan(library.id)
        Scanned(files, items, store.libraryItems(library.id), service.catalog.libraries().any { it.id == library.id })
    }

    private fun Path.file(relative: String) {
        Files.createDirectories(resolve(relative).parent)
        Files.writeString(resolve(relative), "x")
    }

    @Test fun `tracks group by album artist and album, whatever folders they are in`() {
        val s = scan(
            mapOf(
                "Band/Record/01 Opening.flac" to
                    AudioTags("Opening", "Band", album = "Record", track = 1, date = "1999"),
                // Tagged under another folder, and spelled without its article: still the same album.
                "Misc/02.flac" to AudioTags("Second", "The Band", album = "record", track = 2, genres = listOf("Rock")),
                "Mix/CD2/01 - Duet.flac" to AudioTags("Duet", "Guest", "Various Artists", "Mix", 1, 2),
            ),
            "Band/Record/cover.jpg",
            "Band/artist.jpg",
            "Band/Record/notes.txt",
        )
        assertEquals(3, s.files)
        assertEquals(
            setOf("Band", "Various Artists"),
            s.items.filter {
                it.kind == ItemKind.ARTIST
            }.map { it.title }.toSet(),
        )
        assertEquals("Band/artist.jpg", s.byTitle.getValue("Band").poster)
        val record = s.byTitle.getValue("Record")
        assertEquals(ItemKind.ALBUM, record.kind)
        assertEquals(s.byTitle.getValue("Band").id, record.parentId)
        assertEquals("Band/Record/cover.jpg", record.poster)
        assertEquals(1999, record.year)
        assertEquals(400_000, record.durationMs)
        assertEquals(listOf("Rock"), record.details?.genres)
        assertEquals(listOf("Opening", "Second"), s.tracksOf("Record"))
        val duet = s.byTitle.getValue("Duet")
        assertEquals("Guest", duet.details?.artist)
        assertEquals(2, duet.details?.disc)
        assertEquals(s.items.map { it.id }.toSet(), s.rescanned.map { it.id }.toSet(), "a rescan keeps every id")
        assertTrue(!s.listed, "the apps do not list music yet")
    }

    @Test fun `an album without album artist tags is its main artist's, with discs from their folders`() {
        val s = scan(
            mapOf(
                "Duo/Live (2010)/12 Vinyl 02/Duo - Live - 01 - Encore.flac" to
                    AudioTags("Encore", "Duo", album = "Live", track = 1),
                "Duo/Live (2010)/12 Vinyl 01/Duo - Live - 01 - Start.flac" to
                    AudioTags("Start", "Duo", album = "Live", track = 1),
                "Duo/Live (2010)/12 Vinyl 01/Duo - Live - 02 - Guest Spot.flac" to
                    AudioTags("Guest Spot", "Duo feat. Guest", album = "Live", track = 2),
                "Duo/Live (2010)/Duo - Live - 07 - Untagged.mp3" to null,
                "Loner/Demos/CD1/03 - Untagged Song.mp3" to null,
            ),
            "Duo/Live (2010)/12 Vinyl 01/._Duo - Live - 01 - Start.flac",
        )
        assertEquals(5, s.files, "AppleDouble files are not tracks")
        val live = s.byTitle.getValue("Live")
        assertEquals(s.byTitle.getValue("Duo").id, live.parentId)
        assertEquals(listOf("Start", "Guest Spot", "Encore"), s.tracksOf("Live"))
        assertEquals("Duo feat. Guest", s.byTitle.getValue("Guest Spot").details?.artist)
        // Untagged files take their number and title from their names and their album and artist from their folders.
        assertEquals(7, s.byTitle.getValue("Untagged").details?.track)
        assertEquals(listOf("Untagged"), s.tracksOf("Live (2010)"))
        val song = s.byTitle.getValue("Untagged Song")
        assertEquals(3, song.details?.track)
        assertEquals(1, song.details?.disc)
        assertEquals(s.byTitle.getValue("Demos").id, song.parentId)
        assertEquals(s.byTitle.getValue("Loner").id, s.byTitle.getValue("Demos").parentId)
    }
}
