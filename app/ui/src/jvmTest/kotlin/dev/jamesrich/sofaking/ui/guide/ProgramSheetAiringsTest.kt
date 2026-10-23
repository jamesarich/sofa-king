package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.Airing
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.arrowKeysMoveFocus
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** More airings on the details panel: the list, Back to the details, and OK on an airing now and on a later one. */
@OptIn(ExperimentalTestApi::class)
class ProgramSheetAiringsTest {
    private val now = Clock.System.now()
    private val news = Channel(guideId = "c0", number = 2.0, name = "News Now", logo = null, groups = listOf("News"))
    private val movies = Channel(guideId = "c1", number = 3.0, name = "Movie Max", logo = null, groups = listOf("Movies"))
    private fun columbo(channel: Channel, startsIn: kotlin.time.Duration) =
        Program(channelId = channel.guideId, start = now + startsIn, stop = now + startsIn + 1.hours, title = "Columbo", description = null)

    private val shown = columbo(news, 2.hours)
    private val onNow = Airing(movies, columbo(movies, (-10).minutes))
    private val later = Airing(movies, columbo(movies, 5.hours))

    @Test
    fun `More airings lists the others and Back returns to the details`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        var dismissals = 0
        val watched = mutableListOf<Channel>()
        val opened = mutableListOf<Airing>()
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize().arrowKeysMoveFocus(), color = MaterialTheme.colorScheme.background) {
                    ProgramSheet(
                        details = ProgramDetails(news, shown),
                        now = now,
                        isFavorite = false,
                        guideLoading = false,
                        onWatch = { watched += it },
                        onToggleFavorite = {},
                        onDismiss = { dismissals++ },
                        airings = listOf(onNow, later),
                        onShowAiring = { opened += it },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("More airings (2)").performClick()
        waitForIdle()
        onNodeWithText("More airings").assertExists()
        onAllNodesWithText(movies.name, substring = true)[0].assertIsFocused()

        onRoot().performKeyInput { pressKey(Key.Back) }
        waitForIdle()
        assertEquals(0, dismissals, "Back leaves the list, not the details")
        onNodeWithText("More airings (2)").assertIsFocused()

        onNodeWithText("More airings (2)").performClick()
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf(movies), watched, "OK on the airing on now watches its channel")
        onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals(listOf(later), opened, "OK on a later airing opens its details")
    }
}
