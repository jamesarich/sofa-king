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

    @Test fun `compilations tagged track by track, discs in album titles and artist spellings group as one`() {
        val s = scan(
            mapOf(
                // Each track names its own artist as album artist: no artist has most, so it is Various Artists'.
                "Various Artists/Jazz 9/01.flac" to AudioTags("One", "Ann", "Ann", "Jazz 9", 1),
                "Various Artists/Jazz 9/02.flac" to AudioTags("Two", "Bob", "Bob", "Jazz 9", 2),
                "Various Artists/Jazz 9/03.flac" to AudioTags("Three", "Cat", album = "Jazz 9", track = 3),
                "Orb/World/01.flac" to AudioTags("Hum", "The Orb", album = "World CD 1", track = 1, disc = 1),
                "Orb/World/02.flac" to AudioTags("Drone", "The Orb", album = "World (Disc 2)", track = 1, disc = 1),
                "GY/A/01.flac" to AudioTags("Storm", "Godspeed You! Black Emperor", album = "A", track = 1),
                "GY/A/02.flac" to AudioTags("Static", "Godspeed You! Black Emperor", album = "A", track = 2),
                "GY/B/01.flac" to AudioTags("Moya", "Godspeed You Black Emperor!", album = "B", track = 1),
                "Nujabes/Modal/01.flac" to AudioTags("Luv", "Nujabes", "VA", "Modal", 1),
            ),
        )
        val artists = s.items.filter { it.kind == ItemKind.ARTIST }.map { it.title }.toSet()
        assertEquals(setOf("Various Artists", "The Orb", "Godspeed You! Black Emperor"), artists)
        assertEquals(listOf("One", "Two", "Three"), s.tracksOf("Jazz 9"))
        assertEquals(listOf("Hum", "Drone"), s.tracksOf("World"))
        assertEquals(2, s.byTitle.getValue("Drone").details?.disc)
        val godspeed = s.byTitle.getValue("Godspeed You! Black Emperor").id
        assertEquals(godspeed, s.byTitle.getValue("A").parentId)
        assertEquals(godspeed, s.byTitle.getValue("B").parentId)
        assertEquals(s.byTitle.getValue("Various Artists").id, s.byTitle.getValue("Modal").parentId)
    }

    @Test fun `MusicBrainz ids group a release and an artist whatever their names, and are kept`() {
        val release = "9e1a5d1c-8f2b-4a5e-9c3d-1b2a3c4d5e6f"
        val group = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val band = "11111111-2222-4333-8444-555555555555"
        val recording = "22222222-3333-4444-8555-666666666666"
        fun tagged(title: String, artist: String, album: String, track: Int) = AudioTags(
            title, artist, artist, album, track,
            releaseId = release, releaseGroupId = group, albumArtistId = band, artistId = band,
            recordingId = recording.takeIf { track == 1 },
        )
        val s = scan(
            mapOf(
                "Beyonce/Lemonade/01.flac" to tagged("Pray", "Beyoncé", "Lemonade", 1),
                "Beyonce/Lemonade/02.flac" to tagged("Hold Up", "Beyoncé", "Lemonade", 2),
                // Another edition's title, elsewhere, on the same release id.
                "Misc/03.flac" to tagged("Sorry", "Beyoncé", "Lemonade (Deluxe)", 3),
                // The same artist id spelled without the accent, on an album of its own.
                "Beyonce/4/01.flac" to tagged("Love", "Beyonce", "4", 1).copy(
                    releaseId = null,
                    releaseGroupId = null,
                ),
                "Picard/Mix/01.flac" to AudioTags(
                    "Hit",
                    "Ann",
                    "Various Artists",
                    "Mix",
                    1,
                    albumArtistId = "89ad4ac3-39f7-470e-963a-56509c546377",
                ),
            ),
        )
        val artists = s.items.filter { it.kind == ItemKind.ARTIST }
        assertEquals(setOf("Beyoncé", "Various Artists"), artists.map { it.title }.toSet())
        val beyonce = s.byTitle.getValue("Beyoncé")
        assertEquals(band, beyonce.details?.musicBrainzId)
        assertEquals(null, s.byTitle.getValue("Various Artists").details?.musicBrainzId)
        assertEquals(listOf("Pray", "Hold Up", "Sorry"), s.tracksOf("Lemonade"))
        val lemonade = s.byTitle.getValue("Lemonade")
        assertEquals(beyonce.id, lemonade.parentId)
        assertEquals(release, lemonade.details?.musicBrainzId)
        assertEquals(group, lemonade.details?.releaseGroupId)
        assertEquals(beyonce.id, s.byTitle.getValue("4").parentId)
        assertEquals(recording, s.byTitle.getValue("Pray").details?.musicBrainzId)
        assertEquals(s.byTitle.getValue("Various Artists").id, s.byTitle.getValue("Mix").parentId)
    }
}
