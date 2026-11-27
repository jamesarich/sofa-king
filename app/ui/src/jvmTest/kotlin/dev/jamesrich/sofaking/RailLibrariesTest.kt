package dev.jamesrich.sofaking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A rail lists the libraries under Library, each a destination of its own; a bar leaves them to the Library home. */
@OptIn(ExperimentalTestApi::class)
class RailLibrariesTest {
    private val movies = MediaLibrary("movies", "Movies", LibraryKind.MOVIES, itemCount = 84)
    private val shows = MediaLibrary("shows", "TV Shows", LibraryKind.SHOWS, itemCount = 3)

    private fun tabs(
        libraries: List<MediaLibrary> = listOf(movies, shows),
        libraryOpen: String? = null,
        onLibrary: (String) -> Unit = {},
    ) = TabsModel(
        selected = Tab.LIBRARY,
        libraryShown = true,
        recordingsShown = true,
        onTab = {},
        libraries = libraries,
        libraryOpen = libraryOpen,
        onLibrary = onLibrary,
    )

    private fun ComposeUiTest.show(tabs: TabsModel) {
        setContent {
            SofaKingTheme {
                TopLevel(
                    tv = false,
                    tabs = tabs,
                    shown = true,
                    settingsOpen = false,
                    onSettings = {},
                    settingsFocus = remember { FocusRequester() },
                    tabFocus = remember { FocusRequester() },
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `a rail lists every library and a bar none`() {
        val rail = NavigationSuiteType.NavigationRail
        assertEquals(listOf(movies, shows), railLibraries(rail, compactHeight = false, tabs()))
        assertEquals(emptyList(), railLibraries(rail, compactHeight = true, tabs()))
        assertEquals(emptyList(), railLibraries(NavigationSuiteType.ShortNavigationBarCompact, false, tabs()))
        val many = (1..5).map { MediaLibrary("l$it", "Library $it", LibraryKind.VIDEOS, itemCount = 1) }
        assertEquals(emptyList(), railLibraries(rail, compactHeight = false, tabs(many)))
    }

    @Test
    fun `a wide window shows the libraries in the rail between Library and DVR`() =
        runSkikoComposeUiTest(Size(2560f, 1600f), Density(2f)) {
            val picked = mutableListOf<String>()
            show(tabs(onLibrary = { picked += it }))
            val library = onNodeWithText("Library", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val first = onNodeWithText("Movies", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val second = onNodeWithText("TV Shows", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val dvr = onNodeWithText("DVR", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(second.right <= 96.dp, "TV Shows sits in the rail: $second")
            assertTrue(library.top < first.top && first.top < second.top && second.top < dvr.top)
            onNodeWithText("Library").assertIsSelected()
            onNodeWithText("TV Shows").performClick()
            assertEquals(listOf("shows"), picked)
        }

    @Test
    fun `the library whose grid is open is the rail's selected item`() =
        runSkikoComposeUiTest(Size(2560f, 1600f), Density(2f)) {
            show(tabs(libraryOpen = "shows"))
            onNodeWithText("TV Shows").assertIsSelected()
            onNodeWithText("Library").assertIsNotSelected()
            onNodeWithText("Movies").assertIsNotSelected()
        }

    @Test
    fun `a phone's bar keeps to the tabs`() = runSkikoComposeUiTest(Size(720f, 1600f), Density(2f)) {
        show(tabs())
        onNodeWithText("Library").assertExists()
        onNodeWithText("TV Shows").assertDoesNotExist()
    }
}
