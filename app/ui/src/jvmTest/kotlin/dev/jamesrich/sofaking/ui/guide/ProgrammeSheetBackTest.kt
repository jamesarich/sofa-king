package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The remote's Back on the TV details panel. Compose turns an unhandled Back into a focus exit, which the panel
 * cancels to keep focus in it, so Back closed nothing and left focus sunk in the panel.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class ProgrammeSheetBackTest {
    private val now = Clock.System.now()
    private val channel = Channel(guideId = "c0", number = 2.0, name = "News Now", logo = null, groups = listOf("News"))
    private val programme = Programme(channelId = "c0", start = now - 10.minutes, stop = now + 20.minutes, title = "Evening News", description = null)

    @Test
    fun `Back closes the details panel once per press`() = runBlocking {
        var dismissals = 0
        ImageComposeScene(width = 1920, height = 1080, density = Density(2f), coroutineContext = coroutineContext.minusKey(Job)) {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var open by remember { mutableStateOf(true) }
                    if (open) {
                        ProgrammeSheet(
                            details = ProgrammeDetails(channel, programme),
                            now = now,
                            isFavorite = false,
                            guideLoading = false,
                            onWatch = {},
                            onToggleFavorite = {},
                            onDismiss = {
                                dismissals++
                                open = false
                            },
                        )
                    }
                }
            }
        }.use { scene ->
            var frame = 0L
            suspend fun settle() = repeat(20) {
                scene.render(frame)
                frame += 16_000_000L
                delay(16)
            }
            // Watch takes focus a frame or two after the panel attaches.
            settle()
            assertTrue(scene.sendKeyEvent(KeyEvent(Key.Back, KeyEventType.KeyDown)))
            settle()
            assertEquals(0, dismissals, "the panel closes on key-up, so the rest of the press cannot reach the guide")
            assertTrue(scene.sendKeyEvent(KeyEvent(Key.Back, KeyEventType.KeyUp)))
            settle()
            assertEquals(1, dismissals)
        }
    }
}
