package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.util.strippedLicenseContent
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.licenseTargets
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The list the build exports for the licenses page, and the page drawing it on a TV and on a phone. */
@OptIn(ExperimentalTestApi::class, ExperimentalResourceApi::class)
class LicensesScreenTest {
    private val libs: Libs = runBlocking {
        Libs.Builder().withJson(Res.readBytes(LICENSES_FILE).decodeToString()).build()
    }
    private val desktop: List<Library> = librariesFor(libs.libraries, licenseTargets)

    /** Libraries no other one shares a name with, so a name on screen points to one of them. */
    private val named: List<Library> = desktop.filter { library -> desktop.count { it.name == library.name } == 1 }

    @Test
    fun `the build lists each target's libraries with their license texts`() {
        val ktor = libs.libraries.single { it.uniqueId == "io.ktor:ktor-client-core" }
        assertTrue("jvm" in ktor.targets && "android" in ktor.targets, "common libraries carry every target")
        assertTrue(ktor.strippedLicenseContent.contains("Apache License"), "the license text is bundled")
        assertTrue(desktop.any { it.uniqueId == "uk.co.caprica:vlcj" }, "the desktop lists its player")
        assertTrue(desktop.none { it.uniqueId.startsWith("androidx.media3") }, "and not Android's")
    }

    private fun ComposeUiTest.showLicenses(profile: LayoutProfile) {
        setContent {
            CompositionLocalProvider(LocalLayoutProfile provides profile) {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LicensesScreen(onBack = {})
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { count(desktop.first().name) > 0 }
    }

    private fun ComposeUiTest.count(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `a TV shows the first library's license beside the list`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            showLicenses(LayoutProfile.Tv)
            assertTrue(count(desktop.first().name) >= 2, "the name shows in its row and over its license")
        }

    @Test
    fun `a phone opens a license over the list`() = runSkikoComposeUiTest(Size(400f, 800f), Density(1f)) {
        showLicenses(LayoutProfile(tv = false, compactWidth = true, compactHeight = false))
        val first = named.first().name
        assertEquals(1, count(first), "only the list shows until a library is picked")
        onAllNodesWithText(first)[0].performClick()
        waitForIdle()
        assertEquals(1, count(first), "the license replaces the list")
        assertEquals(0, count(named[1].name))
    }
}
