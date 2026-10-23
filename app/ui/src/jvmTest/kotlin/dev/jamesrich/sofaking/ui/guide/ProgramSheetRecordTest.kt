package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMark
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
    private val program = Program(channelId = "c0", start = now + 10.minutes, stop = now + 40.minutes, title = "Evening News", description = null)

    @Test
    fun `Record and Record series follow Close and each call the server once per press`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
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
                        onRecord = { recorded += "one ${it.title}"; true },
                        onRecordSeries = { recorded += "series ${it.title}"; true },
                    )
                }
            }
        }
        fun press(key: Key) {
            onRoot().performKeyInput { pressKey(key) }
            waitForIdle()
        }
        waitForIdle()
        // The desktop scene moves focus with Tab, in the order a D-pad walks the two rows: Watch, Favorite, Close, Record, Record series.
        repeat(3) { press(Key.Tab) }
        press(Key.Enter)
        press(Key.Tab)
        press(Key.Enter)
        assertEquals(listOf("one Evening News", "series Evening News"), recorded)
    }
}
