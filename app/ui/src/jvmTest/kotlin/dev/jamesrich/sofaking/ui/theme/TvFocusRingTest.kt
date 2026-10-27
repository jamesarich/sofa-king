package dev.jamesrich.sofaking.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A focused button's ring traces the pill it draws, not the 48dp touch target Material pads it to: a ring on the
 * padding showed as stray lines a few dp above and below the white pill.
 */
class TvFocusRingTest {
    @Test
    fun `the focus ring hugs the button, not its touch target`() = runBlocking {
        val image = ImageComposeScene(400, 200, Density(2f), coroutineContext = coroutineContext.minusKey(Job)) {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        SofaButton(onClick = {}, modifier = Modifier.focusRequester(focus)) { Text("Back") }
                    }
                }
            }
        }.use { scene ->
            var frame = 0L
            repeat(FRAMES) {
                scene.render(frame)
                frame += FRAME_NANOS
                delay(1)
            }
            scene.render(frame)
        }
        val bitmap = Bitmap.makeFromImage(image)
        val background = bitmap.getColor(image.width - 1, image.height - 1)
        // Down the middle of the button from the top of its 48dp touch target: once the ring starts, the background
        // never shows again before the pill's middle. A ring on the touch target left a gap before the 40dp pill.
        val column = (0..PILL_MIDDLE_ROW).map { bitmap.getColor(PILL_MIDDLE, it) }
        val ring = column.indexOfFirst { it != background }
        assertNotEquals(-1, ring, "the focused button is drawn")
        assertEquals(-1, column.drop(ring).indexOf(background), "no gap between the ring and the pill")
    }

    private companion object {
        const val FRAMES = 40
        const val FRAME_NANOS = 16_000_000L
        const val PILL_MIDDLE = 80
        const val PILL_MIDDLE_ROW = 48
    }
}
