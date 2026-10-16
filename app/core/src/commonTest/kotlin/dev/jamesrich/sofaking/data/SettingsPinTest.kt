package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettingsPinTest {
    @Test
    fun `a PIN is exactly four digits`() {
        assertTrue(SettingsPin.isValid("0420"))
        assertFalse(SettingsPin.isValid("042"))
        assertFalse(SettingsPin.isValid("04201"))
        assertFalse(SettingsPin.isValid("04a0"))
        assertFalse(SettingsPin.isValid(""))
    }

    @Test
    fun `the stored hash matches its own PIN and no other`() {
        val stored = SettingsPin.hash("1234")
        assertTrue(SettingsPin.matches("1234", stored))
        assertFalse(SettingsPin.matches("1235", stored))
        assertFalse(SettingsPin.matches("", stored))
        assertFalse(stored.contains("1234"), "the digits are not in the stored value")
    }

    @Test
    fun `each hash carries its own salt`() {
        assertNotEquals(SettingsPin.hash("1234"), SettingsPin.hash("1234"))
        assertTrue(SettingsPin.matches("1234", SettingsPin.hash("1234", salt = "fixed")))
    }

    @Test
    fun `a value that is not a stored hash matches nothing`() {
        assertFalse(SettingsPin.matches("1234", ""))
        assertFalse(SettingsPin.matches("1234", "1234"))
        assertFalse(SettingsPin.matches("1234", ":abc"))
    }
}
