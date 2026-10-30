package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.Airing
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The TV details panel on a server that records, where the record rows leave the other buttons little height. */
@OptIn(ExperimentalTestApi::class)
class ProgramSheetPanelTest {
    private val now = Clock.System.now()
    private val channel = Channel(guideId = "c7", number = 9.0, name = "Classics", logo = null, groups = emptyList())
    private val program = Program(
        channelId = "c7",
        start = now - 13.minutes,
        stop = now + 17.minutes,
        title = "Classics show 3",
        description = "A fixture program.",
    )
    private val later = Airing(channel, program.copy(start = now + 2.hours, stop = now + 3.hours))

    @Test
    fun `More airings stays on the panel beside the record rows`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            setContent {
                CompositionLocalProvider(LocalLayoutProfile provides LayoutProfile.Tv) {
                    SofaKingTheme {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            ProgramSheet(
                                details = ProgramDetails(channel, program),
                                now = now,
                                isFavorite = false,
                                guideLoading = false,
                                onWatch = {},
                                onToggleFavorite = {},
                                onDismiss = {},
                                recording = RecordingMark(),
                                airings = listOf(later),
                                watchlisted = false,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("More airings (1)").assertIsDisplayed()
        }
}
