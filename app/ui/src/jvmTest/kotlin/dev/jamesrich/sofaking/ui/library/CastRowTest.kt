package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.CastPerson
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cast on a movie's or show's page: a card for each with the role they play once the server knows a role or a
 * photo for any of them, and the names as chips otherwise. Either way OK on one searches for what else they are in.
 */
@OptIn(ExperimentalTestApi::class)
class CastRowTest {
    private val movie = MediaItem("m1", "movies", ItemKind.MOVIE, "Alien")

    private fun ComposeUiTest.show(detail: MediaItemDetail, picked: MutableList<String> = mutableListOf()) {
        setContent {
            CompositionLocalProvider(LocalLayoutProfile provides LayoutProfile.Tv) {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Cast(detail, onPerson = { picked += it }, sides = PaddingValues())
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `each of the cast shows the role they play, and OK on one searches for them`() =
        runSkikoComposeUiTest(Size(1920f, 400f), Density(1f)) {
            val picked = mutableListOf<String>()
            val people = listOf(CastPerson("Ana Reyes", "Dr. Stone"), CastPerson("Tom Hale"))
            show(MediaItemDetail(movie, cast = people.map { it.name }, people = people), picked)
            onAllNodesWithText("Cast").assertCountEquals(1)
            onAllNodesWithText("Dr. Stone").assertCountEquals(1)
            // Their initials stand in for a photo, and a screen reader hears only the name.
            onAllNodesWithText("TH").assertCountEquals(0)
            onNodeWithText("Tom Hale").performClick()
            assertEquals(listOf("Tom Hale"), picked)
        }

    @Test
    fun `names alone stay chips`() = runSkikoComposeUiTest(Size(1920f, 400f), Density(1f)) {
        val picked = mutableListOf<String>()
        show(MediaItemDetail(movie, cast = listOf("Ana Reyes", "Tom Hale")), picked)
        onAllNodesWithText("Cast").assertCountEquals(1)
        onNodeWithText("Ana Reyes").performClick()
        assertEquals(listOf("Ana Reyes"), picked)
    }

    @Test
    fun `cards list twice the cast chips do`() {
        val names = (1..20).map { CastPerson("Actor $it") }
        assertEquals(6, castShown(names).size)
        assertEquals(
            12,
            castShown(
                names.mapIndexed { index, one ->
                    if (index ==
                        3
                    ) {
                        one.copy(role = "Pilot")
                    } else {
                        one
                    }
                },
            ).size,
        )
    }

    @Test
    fun `initials are the first letters of a name's first and last words`() {
        assertEquals("AR", castInitials("Ana Reyes"))
        assertEquals("MD", castInitials("Mary Jo  van Dyke"))
        assertEquals("C", castInitials("cher"))
        assertEquals("", castInitials(" "))
    }
}
