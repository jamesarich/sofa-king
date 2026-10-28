package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.LibraryItem
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicTasteTest {
    private class Setup(val catalog: LibraryCatalog, val library: LibraryRecord, val clock: MutableClock)

    /** Alpha's Album (songs A1 to A6) and Beta's Album (B1 to B2). */
    private fun setup(): Setup = runBlocking {
        val dir = Files.createTempDirectory("sofa-taste-")
        val music = Files.createDirectories(dir.resolve("media/Music"))
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        val tags = buildMap {
            for ((artist, songs) in listOf("Alpha" to 6, "Beta" to 2)) {
                for (n in 1..songs) {
                    put(
                        "$artist/Album/0$n Song.flac",
                        AudioTags("${artist.first()}$n", artist, album = "$artist Album", track = n),
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
        Setup(service.catalog, library, clock)
    }

    private suspend fun Setup.songs(sort: String = "title", favorites: Boolean = false): List<LibraryItem> =
        catalog.items(library.id, sort, false, null, 100, music = true, kind = "track", favorites = favorites).items

    private suspend fun Setup.song(title: String) = songs().single { it.title == title }

    @Test fun `a song is liked and unliked, and only liked ones list as favorites`() = runBlocking<Unit> {
        val s = setup()
        assertFalse(s.song("A1").favorite)
        val liked = s.catalog.setFavorite(s.song("A1").id, true, music = true)
        assertTrue(liked.favorite)
        assertNull(liked.playState, "a like is not a place in the song")
        assertEquals(listOf("A1"), s.songs(favorites = true).map { it.title })
        assertTrue(s.song("A1").favorite)
        assertFalse(s.catalog.setFavorite(s.song("A1").id, false, music = true).favorite)
        assertTrue(s.songs(favorites = true).isEmpty())
    }

    @Test fun `plays are counted and listed most recent and most played first`() = runBlocking<Unit> {
        val s = setup()
        val two = s.song("A2").id
        val five = s.song("A5").id
        s.catalog.recordPlay(two, music = true)
        s.clock.now += Duration.ofMinutes(5)
        s.catalog.recordPlay(five, music = true)
        s.clock.now += Duration.ofMinutes(5)
        val again = s.catalog.recordPlay(two, music = true)
        assertEquals(2, again.plays)
        assertNotNull(again.lastPlayedAt)
        assertEquals(listOf("A2", "A5"), s.songs("played").take(2).map { it.title })
        assertEquals(listOf("A2", "A5"), s.songs("plays").take(2).map { it.title })
        s.clock.now += Duration.ofMinutes(5)
        s.catalog.recordPlay(five, music = true)
        assertEquals(listOf("A5", "A2"), s.songs("played").take(2).map { it.title })
        assertEquals(
            listOf("A2", "A5"),
            s.songs("plays").take(2).map {
                it.title
            },
            "two plays each; the title breaks it",
        )
        val albums = s.catalog.items(s.library.id, "plays", false, null, 100, music = true, kind = "album").items
        assertEquals("Alpha Album", albums.first().title, "an album counts the plays of its songs")
    }

    @Test fun `only a song is played, and an album or artist can be liked`() = runBlocking<Unit> {
        val s = setup()
        val artist = s.catalog.items(s.library.id, null, false, null, 100, music = true).items.first()
        assertTrue(s.catalog.setFavorite(artist.id, true, music = true).favorite)
        assertFailsWith<ApiFailure> { s.catalog.recordPlay(artist.id, music = true) }
        assertFailsWith<ApiFailure> { s.catalog.setFavorite(s.song("A1").id, true, music = false) }
        assertFailsWith<ApiFailure> { s.catalog.setFavorite("nope", true, music = true) }
    }

    @Test fun `a liked song comes up first more often in a radio and one just played less often`() = runBlocking<Unit> {
        val s = setup()
        val snapshot = s.catalog.snapshot()
        val liked = s.song("A1").id
        val recent = s.song("A2").id
        s.catalog.setFavorite(liked, true, music = true)
        s.catalog.recordPlay(recent, music = true)
        val states = s.catalog.playStates()
        val now = s.catalog.clock.instant()
        val trials = 600
        val firsts = HashMap<String, Int>()
        for (seed in 1..trials) {
            val first = radioSongs(snapshot, null, s.library.id, 1, Random(seed), states, now).single()
            firsts.merge(first.record.id, 1, Int::plus)
        }
        val fair = trials / snapshot.songs.getValue(s.library.id).size
        assertTrue((firsts[liked] ?: 0) > fair * 2, "liked ${firsts[liked]} against a fair $fair")
        assertTrue((firsts[recent] ?: 0) < fair / 2, "just played ${firsts[recent]} against a fair $fair")
    }
}
