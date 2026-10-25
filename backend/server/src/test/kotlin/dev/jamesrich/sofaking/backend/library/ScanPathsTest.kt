package dev.jamesrich.sofaking.backend.library

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScanPathsTest {
    private val mappings = pathMappings("/tv=/media/tv, D:\\Movies=/media/movies")

    private fun library(id: String, path: String) =
        LibraryRecord(id, id, LibraryKind.SHOWS, path, "2026-10-25T00:00:00Z")

    @Test fun `a path map reads from=to pairs and refuses a relative target or a missing half`() {
        assertEquals(
            listOf(PathMapping("/tv", Path.of("/media/tv")), PathMapping("D:\\Movies", Path.of("/media/movies"))),
            mappings,
        )
        assertEquals(emptyList<PathMapping>(), pathMappings(null))
        assertFailsWith<IllegalArgumentException> { pathMappings("/tv=media/tv") }
        assertFailsWith<IllegalArgumentException> { pathMappings("/tv") }
        assertFailsWith<IllegalArgumentException> { pathMappings("=/media/tv") }
    }

    @Test fun `a path is mapped at a folder boundary, Windows separators included`() {
        assertEquals(Path.of("/media/tv/Show (2020)/Season 01"), localPath("/tv/Show (2020)/Season 01", mappings))
        assertEquals(Path.of("/media/tv"), localPath("/tv", mappings))
        assertEquals(
            Path.of("/media/movies/Alien (1979)/Alien.mkv"),
            localPath("D:\\Movies\\Alien (1979)\\Alien.mkv", mappings),
        )
        assertEquals(Path.of("/tvshows/Show"), localPath("/tvshows/Show", mappings), "not under /tv")
        assertNull(localPath("relative/Show", mappings))
        assertNull(localPath("E:\\TV\\Show", mappings))
    }

    @Test fun `a change concerns the library holding it, or every library under the folder it names`() {
        val tv = library("tv", "/media/tv")
        val kids = library("kids", "/media/kids")
        val movies = library("movies", "/srv/movies")
        val all = listOf(tv, kids, movies)
        assertEquals(listOf(tv), librariesAt(Path.of("/media/tv/Show/Season 01/a.mkv"), all))
        assertEquals(listOf(tv, kids), librariesAt(Path.of("/media"), all))
        assertEquals(emptyList<LibraryRecord>(), librariesAt(Path.of("/media/tvshows"), all))
        assertEquals(emptyList<LibraryRecord>(), librariesAt(Path.of("/srv/elsewhere"), all))
    }
}
