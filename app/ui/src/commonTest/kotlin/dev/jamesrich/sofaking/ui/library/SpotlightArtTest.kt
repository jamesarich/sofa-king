package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotlightArtTest {
    private fun item(poster: String? = null, backdrop: String? = null) =
        MediaItem("m", "lib", ItemKind.MOVIE, "Movie", poster = poster, backdrop = backdrop)

    @Test
    fun `the spotlight and its page tint draw the backdrop`() {
        assertEquals("b", item(poster = "p", backdrop = "b").spotlightArt())
    }

    @Test
    fun `a poster stands in for a missing backdrop and no art gives no tint`() {
        assertEquals("p", item(poster = "p").spotlightArt())
        assertNull(item().spotlightArt())
    }
}
