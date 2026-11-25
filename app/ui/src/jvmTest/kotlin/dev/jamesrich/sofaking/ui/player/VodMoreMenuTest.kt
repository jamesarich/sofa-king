package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class VodMoreMenuTest {
    private fun moreMenu(speedAdjustable: Boolean, pressed: MutableList<VodButton>): List<PlayerMenuEntry> {
        var menu: List<PlayerMenuEntry>? = null
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            setContent {
                SofaKingTheme {
                    menu = vodButtonItem(
                        VodButton.MORE,
                        captionsOn = false,
                        zoom = VideoZoom.FIT,
                        autoSkipSeconds = null,
                        creditsToNext = false,
                        sleep = remember { SleepTimer() },
                        speed = 1.25f,
                        onClick = {},
                        speedAdjustable = speedAdjustable,
                        onMenuEntry = { pressed += it },
                    ).menu
                }
            }
            waitForIdle()
        }
        return assertNotNull(menu)
    }

    @Test
    fun `More offers zoom and sleep and adds speed where the engine changes it`() {
        val pressed = mutableListOf<VodButton>()
        val without = moreMenu(speedAdjustable = false, pressed)
        assertEquals(listOf("Zoom: Fit", "Sleep timer"), without.map { it.label })
        val with = moreMenu(speedAdjustable = true, pressed)
        assertEquals(3, with.size)
        assertTrue(with[2].label.contains("1.25"), with[2].label)
    }

    @Test
    fun `an entry steps its setting and the menu stays up`() {
        val pressed = mutableListOf<VodButton>()
        val entries = moreMenu(speedAdjustable = true, pressed)
        entries.forEach {
            assertTrue(it.keepOpen)
            it.onClick()
        }
        assertEquals(listOf(VodButton.ZOOM, VodButton.SLEEP, VodButton.SPEED), pressed)
    }
}
