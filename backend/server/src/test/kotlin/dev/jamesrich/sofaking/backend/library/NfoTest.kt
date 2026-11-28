package dev.jamesrich.sofaking.backend.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NfoTest {
    @Test fun `a movie nfo gives its title, year, plot, genres and default rating`() {
        val nfo = parseNfo(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <movie>
              <title>Alien</title><year>1979</year><plot>In space no one can hear you scream.</plot>
              <runtime>117</runtime><genre>Horror</genre><genre>Science Fiction</genre><mpaa>Rated R</mpaa>
              <ratings><rating name="imdb"><value>8.5</value></rating><rating name="tmdb" default="true"><value>8.1</value></rating></ratings>
              <actor><name>Sigourney Weaver</name></actor>
            </movie>
            """.trimIndent().byteInputStream(),
        )!!
        assertEquals("Alien", nfo.title)
        assertEquals(1979, nfo.year)
        assertEquals(117, nfo.runtimeMinutes)
        assertEquals(listOf("Horror", "Science Fiction"), nfo.genres)
        assertEquals(8.1, nfo.rating)
        assertEquals(listOf("Sigourney Weaver"), nfo.cast)
    }

    @Test fun `an nfo gives each actor's role, and leaves out the role of one it has none for`() {
        val nfo = parseNfo(
            (
                "<movie><actor><name>Sigourney Weaver</name><role>Ripley</role><thumb>https://x.test/w.jpg</thumb>" +
                    "</actor><actor><name>Tom Skerritt</name><role> </role></actor><actor><role>Nobody</role></actor>" +
                    "</movie>"
                ).byteInputStream(),
        )!!
        assertEquals(listOf("Sigourney Weaver", "Tom Skerritt"), nfo.cast)
        assertEquals(mapOf("Sigourney Weaver" to "Ripley"), nfo.roles)
    }

    @Test fun `an nfo's ratings give each named source's score once, under the names the tools write`() {
        val nfo = parseNfo(
            (
                "<movie><ratings>" +
                    """<rating name="imdb" max="10" default="true"><value>7.8</value><votes>1,234</votes></rating>""" +
                    """<rating name="themoviedb" max="10"><value>7.4</value><votes>900</votes></rating>""" +
                    """<rating name="tomatometerallcritics" max="100"><value>91</value></rating>""" +
                    """<rating name="tomatometerallaudience" max="100"><value>85</value></rating>""" +
                    """<rating name="metacritic" max="100"><value>74</value></rating>""" +
                    """<rating name="imdb"><value>6.0</value></rating>""" +
                    """<rating name="someone"><value>5</value></rating>""" +
                    """<rating name="trakt"><value>11</value></rating>""" +
                    """<rating name="letterboxd" max="5"><value></value></rating>""" +
                    "</ratings></movie>"
                ).byteInputStream(),
        )!!
        assertEquals(
            listOf(
                Rating("imdb", 7.8, 10.0, 1234),
                Rating("tmdb", 7.4, 10.0, 900),
                Rating("rotten_tomatoes", 91.0, 100.0),
                Rating("rotten_tomatoes_audience", 85.0, 100.0),
                Rating("metacritic", 74.0, 100.0),
            ),
            nfo.ratings,
        )
        assertEquals(7.8, nfo.rating, "the default rating stays the one a card shows")
    }

    @Test fun `an nfo names a movie's collection in set, as a name inside it or as its text`() {
        fun collection(xml: String) = parseNfo("<movie>$xml</movie>".byteInputStream())!!.collection
        assertEquals(
            "Alien Collection",
            collection("<set><name> Alien Collection </name><overview>All of them.</overview></set>"),
        )
        assertEquals("Alien Collection", collection("<set>Alien Collection</set>"))
        assertEquals(null, collection("<set><overview>No name.</overview></set>"))
        assertEquals(null, collection("<set></set>"))
        assertEquals(null, collection("<title>Alien</title>"))
    }

    @Test fun `an nfo gives its TMDB, TheTVDB and IMDb ids from uniqueid or the older tags`() {
        val show = parseNfo(
            (
                """<tvshow><uniqueid type="tvdb" default="true">81189</uniqueid>""" +
                    """<uniqueid type="imdb">tt0903747</uniqueid><uniqueid type="tmdb">1396</uniqueid></tvshow>"""
                ).byteInputStream(),
        )!!
        assertEquals(1396, show.tmdbId)
        assertEquals(81189, show.tvdbId)
        assertEquals("tt0903747", show.imdbId)
        val movie = parseNfo("<movie><id>tt0078748</id><tmdbid>348</tmdbid></movie>".byteInputStream())!!
        assertEquals("tt0078748", movie.imdbId)
        assertEquals(348, movie.tmdbId)
        assertNull(parseNfo("<movie><id>12345</id></movie>".byteInputStream())!!.imdbId, "another scraper's id")
    }

    @Test fun `an episode nfo gives its numbers and air date`() {
        val nfo = parseNfo(
            (
                "<episodedetails><title>Pilot</title><season>1</season><episode>2</episode><aired>2026-10-17</" +
                    "aired></episodedetails>"
                ).byteInputStream(),
        )!!
        assertEquals(1, nfo.season)
        assertEquals(2, nfo.episode)
        assertEquals(2026, nfo.year)
    }

    @Test fun `a doctype, a bare url and another root are refused`() {
        assertNull(
            parseNfo(
                (
                    """<?xml version="1.0"?><!DOCTYPE movie [<!ENTITY x SYSTEM """ +
                        """"file:///etc/passwd">]><movie><title>&x;</title></movie>"""
                    ).byteInputStream(),
            ),
        )
        assertNull(parseNfo("https://www.themoviedb.org/movie/348".byteInputStream()))
        assertNull(parseNfo("<album><title>No</title></album>".byteInputStream()))
    }
}
