package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.settings_surround_test
import dev.jamesrich.sofaking.resources.settings_surround_test_help
import dev.jamesrich.sofaking.resources.settings_surround_test_play
import dev.jamesrich.sofaking.resources.settings_surround_test_stop
import dev.jamesrich.sofaking.resources.speaker_center
import dev.jamesrich.sofaking.resources.speaker_front_left
import dev.jamesrich.sofaking.resources.speaker_front_right
import dev.jamesrich.sofaking.resources.speaker_subwoofer
import dev.jamesrich.sofaking.resources.speaker_surround_left
import dev.jamesrich.sofaking.resources.speaker_surround_right
import dev.jamesrich.sofaking.ui.player.Speaker
import dev.jamesrich.sofaking.ui.player.SurroundTone
import dev.jamesrich.sofaking.ui.rememberSurroundTestPlayer
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeSource

/** How often the row looks at the time played to name the speaker that should sound. */
private const val SPEAKER_CHECK_MS = 100L

/**
 * Settings' surround test: Play sounds a tone from each speaker of a 5.1 setup in turn and names the one that should
 * sound; Stop, or the end of the test, ends it. Nothing where the platform has no player for it.
 */
@Composable
internal fun SurroundTestRow() {
    val player = rememberSurroundTestPlayer() ?: return
    var started by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    var speaker by remember { mutableStateOf<Speaker?>(null) }
    LaunchedEffect(started) {
        val mark = started
        if (mark == null) {
            speaker = null
            return@LaunchedEffect
        }
        while (true) {
            speaker = SurroundTone.speakerAt(mark.elapsedNow().inWholeMilliseconds) ?: break
            delay(SPEAKER_CHECK_MS)
        }
        started = null
    }
    val playing = started != null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingRow(label = stringResource(Res.string.settings_surround_test)) {
            SofaButton(
                onClick = {
                    if (started == null) {
                        player.play()
                        started = TimeSource.Monotonic.markNow()
                    } else {
                        player.stop()
                        started = null
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (playing) Res.string.settings_surround_test_stop else Res.string.settings_surround_test_play,
                    ),
                )
            }
            speaker?.let { Text(speakerName(it), style = MaterialTheme.typography.bodyLarge) }
        }
        HelpText(stringResource(Res.string.settings_surround_test_help))
    }
}

@Composable
private fun speakerName(speaker: Speaker): String = stringResource(
    when (speaker) {
        Speaker.FRONT_LEFT -> Res.string.speaker_front_left
        Speaker.CENTER -> Res.string.speaker_center
        Speaker.FRONT_RIGHT -> Res.string.speaker_front_right
        Speaker.SURROUND_RIGHT -> Res.string.speaker_surround_right
        Speaker.SURROUND_LEFT -> Res.string.speaker_surround_left
        Speaker.SUBWOOFER -> Res.string.speaker_subwoofer
    },
)
