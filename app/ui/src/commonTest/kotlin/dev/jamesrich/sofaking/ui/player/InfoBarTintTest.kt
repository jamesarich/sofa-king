package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import dev.jamesrich.sofaking.ui.ambientTint
import dev.jamesrich.sofaking.ui.contrastRatio
import dev.jamesrich.sofaking.ui.theme.SofaDusk
import dev.jamesrich.sofaking.ui.theme.SofaMauve
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaPlum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfoBarTintTest {
    @Test
    fun `no logo color leaves the bar at the overlay scrim`() {
        assertEquals(SofaOverlayScrim, infoBarEdge(SofaPlum))
    }

    @Test
    fun `the lightest tint keeps text readable over a white picture`() {
        val bar = infoBarEdge(ambientTint(Color.White)).compositeOver(Color.White)
        assertTrue(contrastRatio(SofaMauve, bar) >= AA, "mauve ${contrastRatio(SofaMauve, bar)}")
        assertTrue(contrastRatio(SofaDusk, bar) >= 7f, "dusk ${contrastRatio(SofaDusk, bar)}")
    }

    private companion object {
        const val AA = 4.5f
    }
}
