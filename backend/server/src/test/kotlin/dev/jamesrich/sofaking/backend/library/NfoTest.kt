package dev.jamesrich.sofaking.backend.library

import kotlin.test.*

class NfoTest {
    @Test fun `a movie nfo gives its title, year, plot, genres and default rating`() {
        val nfo = parseNfo("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <movie>
              <title>Alien</title><year>1979</year><plot>In space no one can hear you scream.</plot>
              <runtime>117</runtime><genre>Horror</genre><genre>Science Fiction</genre><mpaa>Rated R</mpaa>
              <ratings><rating name="imdb"><value>8.5</value></rating><rating name="tmdb" default="true"><value>8.1</value></rating></ratings>
              <actor><name>Sigourney Weaver</name></actor>
            </movie>
        """.trimIndent().byteInputStream())!!
        assertEquals("Alien", nfo.title)
        assertEquals(1979, nfo.year)
        assertEquals(117, nfo.runtimeMinutes)
        assertEquals(listOf("Horror", "Science Fiction"), nfo.genres)
        assertEquals(8.1, nfo.rating)
        assertEquals(listOf("Sigourney Weaver"), nfo.cast)
    }

    @Test fun `an episode nfo gives its numbers and air date`() {
        val nfo = parseNfo("<episodedetails><title>Pilot</title><season>1</season><episode>2</episode><aired>2026-10-17</aired></episodedetails>".byteInputStream())!!
        assertEquals(1, nfo.season)
        assertEquals(2, nfo.episode)
        assertEquals(2026, nfo.year)
    }

    @Test fun `a doctype, a bare url and another root are refused`() {
        assertNull(parseNfo("""<?xml version="1.0"?><!DOCTYPE movie [<!ENTITY x SYSTEM "file:///etc/passwd">]><movie><title>&x;</title></movie>""".byteInputStream()))
        assertNull(parseNfo("https://www.themoviedb.org/movie/348".byteInputStream()))
        assertNull(parseNfo("<album><title>No</title></album>".byteInputStream()))
    }
}
