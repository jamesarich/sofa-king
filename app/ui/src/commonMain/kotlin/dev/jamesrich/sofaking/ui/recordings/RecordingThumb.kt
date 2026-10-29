package dev.jamesrich.sofaking.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.data.recordedSoFar
import dev.jamesrich.sofaking.data.resumeFraction
import dev.jamesrich.sofaking.ui.Artwork
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.ProgressBar
import dev.jamesrich.sofaking.ui.WIDESCREEN
import dev.jamesrich.sofaking.ui.rememberMinuteNow

/** A row's picture is this wide: a TV's matches the row's text and buttons in height, a narrow window's is small. */
@Composable
internal fun recordingThumbWidth(): Dp = if (LocalLayoutProfile.current.compactWidth) 112.dp else 192.dp

/** The recording's 16:9 picture with a bar along its bottom for how far the household got. Not focusable. */
@Composable
internal fun RecordingThumb(recording: DvrRecording, modifier: Modifier = Modifier) {
    Box(
        modifier.width(recordingThumbWidth()).aspectRatio(WIDESCREEN).clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Artwork(recording.imageUrl, label = null)
        recording.resumeFraction?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

/** How much of a running recording is on disk, read against the minute clock; nothing for any other state. */
@Composable
internal fun RecordedSoFarBar(recording: DvrRecording, modifier: Modifier = Modifier) {
    if (recording.state != RecordingState.RECORDING) return
    val now by rememberMinuteNow()
    recording.recordedSoFar(now)?.let {
        ProgressBar(it, modifier, color = MaterialTheme.colorScheme.tertiary)
    }
}
