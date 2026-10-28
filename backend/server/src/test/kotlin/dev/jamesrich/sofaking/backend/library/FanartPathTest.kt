package dev.jamesrich.sofaking.backend.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FanartPathTest {
    @Test fun `fanart tv's slug named and foldered images are kept and other addresses are not`() {
        assertEquals("autechre-4faa1c.jpg", fanartPath("https://assets.fanart.tv/fanart/autechre-4faa1c.jpg"))
        assertEquals(
            "music/f22942a1/artistbackground/band-3.png",
            fanartPath("http://assets.fanart.tv/fanart/music/f22942a1/artistbackground/band-3.png"),
        )
        assertNull(fanartPath("https://assets.fanart.tv/fanart/../secret.jpg"))
        assertNull(fanartPath("https://assets.fanart.tv/fanart/a/b/c/d/e.jpg"))
        assertNull(fanartPath("https://elsewhere.example/fanart/band.jpg"))
        assertNull(fanartPath("https://assets.fanart.tv/fanart/band.gif"))
    }
}
