package dev.jamesrich.sofaking.ui.theme

import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Appearance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeChoiceTest {
    @Test
    fun `a TV is dark whatever is chosen and whatever the system says`() {
        for (appearance in Appearance.entries) {
            for (systemDark in listOf(true, false)) {
                assertTrue(useDarkTheme(appearance, television = true, systemDark = systemDark))
            }
        }
    }

    @Test
    fun `system follows the platform's dark mode off a TV`() {
        assertTrue(useDarkTheme(Appearance.SYSTEM, television = false, systemDark = true))
        assertFalse(useDarkTheme(Appearance.SYSTEM, television = false, systemDark = false))
    }

    @Test
    fun `dark and light pin the scheme off a TV`() {
        for (systemDark in listOf(true, false)) {
            assertTrue(useDarkTheme(Appearance.DARK, television = false, systemDark = systemDark))
            assertFalse(useDarkTheme(Appearance.LIGHT, television = false, systemDark = systemDark))
        }
    }

    @Test
    fun `a new device follows the system`() {
        assertEquals(Appearance.SYSTEM, AppSettings().appearance)
    }
}
