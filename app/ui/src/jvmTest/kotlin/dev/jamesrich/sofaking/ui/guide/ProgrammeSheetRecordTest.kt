package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Record and Record series on the TV details panel, on the row below Watch, Favorite and Close. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class ProgrammeSheetRecordTest {
    private val now = Clock.System.now()
    private val channel = Channel(guideId = "c0", number = 2.0, name = "News Now", logo = null, groups = listOf("News"))
    private val programme = Programme(channelId = "c0", start = now + 10.minutes, stop = now + 40.minutes, title = "Evening News", description = null)

    @Test
    fun `Record and Record series follow Close and each call the server once per press`() = runBlocking {
        val recorded = mutableListOf<String>()
        ImageComposeScene(width = 1920, height = 1080, density = Density(2f), coroutineContext = coroutineContext.minusKey(Job)) {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ProgrammeSheet(
                        details = ProgrammeDetails(channel, programme),
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
        }.use { scene ->
            var frame = 0L
            suspend fun settle() = repeat(20) {
                scene.render(frame)
                frame += 16_000_000L
                delay(16)
            }
            suspend fun press(key: Key) {
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
                settle()
            }
            settle()
            // The desktop scene moves focus with Tab, in the order a D-pad walks the two rows: Watch, Favorite, Close, Record, Record series.
            repeat(3) { press(Key.Tab) }
            press(Key.Enter)
            press(Key.Tab)
            press(Key.Enter)
            assertEquals(listOf("one Evening News", "series Evening News"), recorded)
        }
    }
}
