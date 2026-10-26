package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.ui.theme.SofaOverlayTrack

/**
 * How far along something is ([fraction], 0..1), as Material's linear progress indicator in a thin full-width bar: a
 * program on the info bar and the mini guide, a title on its card or episode row. [color] is the filled part, for rows
 * whose background is already primary.
 */
@Composable
internal fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().height(4.dp),
        color = color,
        trackColor = SofaOverlayTrack,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
