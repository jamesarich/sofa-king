package dev.jamesrich.sofaking.ui.settings

import androidx.compose.ui.input.key.Key
import com.mikepenz.aboutlibraries.entity.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LicensesTest {
    private fun library(id: String, vararg targets: String) = Library(
        uniqueId = id,
        artifactVersion = "1.0.0",
        name = id,
        description = null,
        website = null,
        developers = emptyList(),
        organization = null,
        scm = null,
        targets = targets.toSet(),
    )

    @Test
    fun `a platform lists only the libraries its targets ship`() {
        val common = library("common", "android", "jvm", "iosArm64", "js", "wasmJs")
        val media3 = library("media3", "android")
        val vlcj = library("vlcj", "jvm")
        val all = listOf(common, media3, vlcj)
        assertEquals(listOf(common, media3), librariesFor(all, setOf("android")))
        assertEquals(listOf(common, vlcj), librariesFor(all, setOf("jvm")))
        assertEquals(listOf(common), librariesFor(all, setOf("js", "wasmJs")))
    }

    @Test
    fun `a list without targets is shown whole`() {
        val all = listOf(library("a"), library("b"))
        assertEquals(all, librariesFor(all, setOf("iosArm64", "iosSimulatorArm64")))
    }

    @Test
    fun `beside the list the first license shows until another is focused`() {
        val all = listOf(library("a"), library("b"))
        assertEquals(all[0], shownLibrary(all, open = null, twoPane = true))
        assertEquals(all[1], shownLibrary(all, open = "b", twoPane = true))
    }

    @Test
    fun `over the list a license shows only once one is opened`() {
        val all = listOf(library("a"), library("b"))
        assertNull(shownLibrary(all, open = null, twoPane = false))
        assertEquals(all[1], shownLibrary(all, open = "b", twoPane = false))
    }

    @Test
    fun `Back closes a license over the list before it leaves the page`() {
        assertTrue(licensesBackClosesLicense(twoPane = false, open = "ktor"))
        assertFalse(licensesBackClosesLicense(twoPane = false, open = null))
        assertFalse(licensesBackClosesLicense(twoPane = true, open = "ktor"))
    }

    @Test
    fun `Down scrolls three quarters of the view and stops at the end`() {
        assertEquals(750, licenseScrollStep(Key.DirectionDown, value = 0, max = 5_000, viewport = 1_000))
        assertEquals(200, licenseScrollStep(Key.DirectionDown, value = 4_800, max = 5_000, viewport = 1_000))
        assertNull(licenseScrollStep(Key.DirectionDown, value = 5_000, max = 5_000, viewport = 1_000))
    }

    @Test
    fun `Up scrolls back and hands focus on at the top`() {
        assertEquals(-750, licenseScrollStep(Key.DirectionUp, value = 2_000, max = 5_000, viewport = 1_000))
        assertEquals(-100, licenseScrollStep(Key.DirectionUp, value = 100, max = 5_000, viewport = 1_000))
        assertNull(licenseScrollStep(Key.DirectionUp, value = 0, max = 5_000, viewport = 1_000))
    }

    @Test
    fun `other keys leave the license to focus`() {
        assertNull(licenseScrollStep(Key.DirectionLeft, value = 100, max = 5_000, viewport = 1_000))
        assertNull(licenseScrollStep(Key.DirectionCenter, value = 100, max = 5_000, viewport = 1_000))
    }
}
