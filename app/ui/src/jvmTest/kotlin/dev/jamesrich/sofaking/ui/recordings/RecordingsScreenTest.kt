package dev.jamesrich.sofaking.ui.recordings

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
import dev.jamesrich.sofaking.RecordingsState
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingSpace
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The Recordings list as the D-pad walks it: each row's buttons in turn, with a second press to delete. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class RecordingsScreenTest {
    private fun recording(id: String, state: RecordingState, positionMs: Long = 0) = DvrRecording(
        id = id, channelId = "c1", channelName = "News 4", title = "Title $id", episodeTitle = null, description = null,
        season = null, episode = null, imageUrl = null, startsAt = Instant.parse("2026-10-18T18:00:00Z"),
        endsAt = Instant.parse("2026-10-18T18:30:00Z"), state = state, reason = null, bytes = 0, protected = false,
        positionMs = positionMs, watched = false, breaks = emptyList(),
    )

    @Test
    fun `focus starts on the running recording and a delete takes a second press`() = runBlocking {
        val calls = mutableListOf<String>()
        val state = RecordingsState.Ready(
            listOf(recording("done", RecordingState.COMPLETED, positionMs = 60_000), recording("now", RecordingState.RECORDING)),
            RecordingSpace(0, 0),
        )
        ImageComposeScene(width = 1920, height = 1080, density = Density(2f), coroutineContext = coroutineContext.minusKey(Job)) {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RecordingsScreen(
                        state = state,
                        onPlay = { r, fromStart -> calls += "play ${r.id} $fromStart" },
                        onDelete = { calls += "delete ${it.id}"; true },
                        onRetry = {},
                        onBack = {},
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
            // The desktop scene moves focus with Tab, in the order a D-pad walks the rows: Play, Stop, then Resume, From start.
            press(Key.Enter)
            press(Key.Tab)
            press(Key.Enter)
            assertEquals(listOf("play now false"), calls, "the first press only asks")
            press(Key.Enter)
            press(Key.Tab)
            press(Key.Tab)
            press(Key.Enter)
            assertEquals(listOf("play now false", "delete now", "play done true"), calls)
        }
    }
}
