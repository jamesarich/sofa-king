package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MusicRadioTest {
    private class Setup(val catalog: LibraryCatalog, val library: LibraryRecord)

    /** Rock's Alpha (five songs) and Beta (five), Jazz's Gamma (three), and a movie library beside the music. */
    private fun setup(): Setup = runBlocking {
        val dir = Files.createTempDirectory("sofa-radio-")
        val music = Files.createDirectories(dir.resolve("media/Music"))
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        val tags = buildMap {
            val artists = listOf(Triple("Alpha", "Rock", 5), Triple("Beta", "Rock", 5), Triple("Gamma", "Jazz", 3))
            for ((artist, genre, songs) in artists) {
                for (n in 1..songs) {
                    put(
                        "$artist/Album/0$n Song.flac",
                        AudioTags("$artist $n", artist, album = "Album", track = n, genres = listOf(genre)),
                    )
                }
            }
        }
        for (path in tags.keys) {
            Files.createDirectories(music.resolve(path).parent)
            Files.writeString(music.resolve(path), "x")
        }
        val scanner = LibraryScanner({ file ->
            MediaInfo(
                "flac",
                durationMs = 180_000,
                audio = listOf(AudioStream(0, "flac")),
                tags = tags[music.relativize(file).joinToString("/")],
            )
        })
        val service =
            LibraryService(store, clock, listOf(MediaRoot("Media", dir.resolve("media"))), scanner, Jobs(clock))
        val library = service.create("Music", LibraryKind.MUSIC, music.toString())
        service.scan(library.id)
        Setup(service.catalog, library)
    }

    private suspend fun Setup.artist(name: String): LibraryItem =
        catalog.items(library.id, null, false, null, 100, music = true).items.single { it.title == name }

    @Test fun `an artist's radio plays their songs and those of artists sharing their genre, not other genres`() =
        runBlocking<Unit> {
            val s = setup()
            val alpha = s.artist("Alpha")
            val songs = s.catalog.radio(alpha.id, 50, music = true, random = Random(1)).items
            assertEquals(10, songs.size, "Alpha's five and Beta's five")
            assertEquals(songs.size, songs.map { it.id }.toSet().size, "each once")
            assertTrue(songs.all { it.kind == Wire.ItemKind.TRACK })
            assertEquals(setOf("Alpha", "Beta"), songs.map { it.artist }.toSet())
        }

    @Test fun `no more than two songs of one artist play in a row while another has songs left`() = runBlocking<Unit> {
        val s = setup()
        val alpha = s.artist("Alpha")
        for (seed in 1..20) {
            val songs = s.catalog.radio(alpha.id, 50, music = true, random = Random(seed)).items
            for (at in 2 until songs.size) {
                val third = songs.subList(at - 2, at + 1).map { it.artistId }.distinct().size == 1
                val others = songs.drop(at + 1).any { it.artistId != songs[at].artistId }
                assertTrue(!third || !others, songs.joinToString { it.title })
            }
        }
    }

    @Test fun `a song starts its own radio and an album opens with its first song`() = runBlocking<Unit> {
        val s = setup()
        val alpha = s.artist("Alpha")
        val album = s.catalog.item(alpha.id, music = true).children.single()
        val songs = s.catalog.item(album.id, music = true).children
        val fromSong = s.catalog.radio(songs[2].id, 5, music = true, random = Random(3)).items
        assertEquals(songs[2].id, fromSong.first().id)
        assertEquals(5, fromSong.size, "at most the limit")
        val fromAlbum = s.catalog.radio(album.id, 5, music = true, random = Random(3)).items
        assertEquals(songs[0].id, fromAlbum.first().id)
    }

    @Test fun `a library's radio draws from every artist`() = runBlocking<Unit> {
        val s = setup()
        val songs = s.catalog.libraryRadio(s.library.id, 50, music = true, random = Random(2)).items
        assertEquals(13, songs.size)
        assertEquals(setOf("Alpha", "Beta", "Gamma"), songs.map { it.artist }.toSet())
    }

    @Test fun `a radio is for music clients and for artists, albums and songs`() = runBlocking<Unit> {
        val s = setup()
        val alpha = s.artist("Alpha")
        assertFailsWith<ApiFailure> { s.catalog.radio(alpha.id, 10, music = false) }
        assertFailsWith<ApiFailure> { s.catalog.libraryRadio(s.library.id, 10, music = false) }
        assertFailsWith<ApiFailure> { s.catalog.radio("missing", 10, music = true) }
    }
}
