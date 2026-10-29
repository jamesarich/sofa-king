package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.ProgramKind
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.RecordingPadding
import dev.jamesrich.sofaking.data.RecordingWindow
import dev.jamesrich.sofaking.data.SeriesOptions
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Record and Record series on the TV details panel, on the row below Watch, Favorite and Close. */
@OptIn(ExperimentalTestApi::class)
class ProgramSheetRecordTest {
    private val now = Clock.System.now()
    private val channel = Channel(guideId = "c0", number = 2.0, name = "News Now", logo = null, groups = listOf("News"))
    private val program =
        Program(
            channelId = "c0",
            start = now + 10.minutes,
            stop = now + 40.minutes,
            title = "Evening News",
            description = null,
        )

    @Test
    fun `Record and Record series follow Close and each call the server once per press`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val recorded = mutableListOf<String>()
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProgramSheet(
                            details = ProgramDetails(channel, program),
                            now = now,
                            isFavorite = false,
                            guideLoading = false,
                            onWatch = { recorded += "watch" },
                            onToggleFavorite = { recorded += "fav" },
                            onDismiss = {},
                            recording = RecordingMark(),
                            onRecord = {
                                recorded += "one ${it.title}"
                                true
                            },
                            onRecordSeries = { aired, _ ->
                                recorded += "series ${aired.title}"
                                true
                            },
                        )
                    }
                }
            }
            fun press(key: Key) {
                onRoot().performKeyInput { pressKey(key) }
                waitForIdle()
            }
            waitForIdle()
            // The desktop scene moves focus with Tab, in the order a D-pad walks the two rows: Watch,
            // Favorite, Close, Record, Record series.
            repeat(3) { press(Key.Tab) }
            press(Key.Enter)
            press(Key.Tab)
            press(Key.Enter)
            assertEquals(listOf("one Evening News", "series Evening News"), recorded)
        }

    @Test
    fun `a sports program offers Record for each team and Stop recording for one already recorded`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val teams = mutableListOf<String>()
            val game = program.copy(
                title = "NFL Football",
                subTitle = "Bears at Packers",
                kinds = setOf(ProgramKind.SPORTS),
            )
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProgramSheet(
                            details = ProgramDetails(channel, game),
                            now = now,
                            isFavorite = false,
                            guideLoading = false,
                            onWatch = {},
                            onToggleFavorite = {},
                            onDismiss = {},
                            recording = RecordingMark(teams = setOf("packers")),
                            onRecordTeam = {
                                teams += it
                                true
                            },
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Record Bears").assertExists()
            onNodeWithText("Stop recording Packers").performClick()
            waitForIdle()
            assertEquals(listOf("Packers"), teams)
        }

    @Test
    fun `Record series sends the padding and channel the option buttons were set to`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val seen = mutableListOf<SeriesOptions>()
            setContent {
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
                            onRecordSeries = { _, options ->
                                seen += options
                                true
                            },
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Padding: Default").performClick()
            onNodeWithText("This channel only").performClick()
            waitForIdle()
            onNodeWithText("Padding: None").assertExists()
            onNodeWithText("Record series").performClick()
            waitForIdle()
            assertEquals(listOf(SeriesOptions(RecordingPadding.NONE, anyChannel = true)), seen)
        }

    @Test
    fun `a series already recorded hides the option buttons`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            setContent {
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
                            recording = RecordingMark(series = true),
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Stop series").assertExists()
            onNodeWithText("Padding: Default").assertDoesNotExist()
        }

    @Test
    fun `a channel with no guide still offers the window buttons`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val windows = mutableListOf<Pair<String, RecordingWindow>>()
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProgramSheet(
                            details = ProgramDetails(channel, null),
                            now = now,
                            isFavorite = false,
                            guideLoading = false,
                            onWatch = {},
                            onToggleFavorite = {},
                            onDismiss = {},
                            recording = RecordingMark(),
                            onRecordWindow = { channel, window ->
                                windows += channel.guideId to window
                                true
                            },
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Record").assertDoesNotExist()
            onNodeWithText("Record next hour").performClick()
            waitForIdle()
            onNodeWithText("Record next 2 hours").performClick()
            waitForIdle()
            assertEquals(listOf("c0" to RecordingWindow.ONE_HOUR, "c0" to RecordingWindow.TWO_HOURS), windows)
            onNodeWithText("Recording started").assertExists()
        }

    @Test
    fun `a server that does not record shows no window buttons`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            setContent {
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
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Record next hour").assertDoesNotExist()
        }

    @Test
    fun `the keyword button names the search text and sends it`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val keywords = mutableListOf<String>()
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        KeywordRecordButton("  world cup ", RecordingMarks()) {
                            keywords += it
                            true
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Record everything matching “world cup”").performClick()
            waitForIdle()
            assertEquals(listOf("world cup"), keywords)
        }

    @Test
    fun `the keyword button waits for a query long enough to be a keyword`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        KeywordRecordButton("w", RecordingMarks()) { true }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Record everything matching", substring = true).assertDoesNotExist()
        }
}
