package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.VodMarker
import dev.jamesrich.sofaking.data.VodMarkerKind
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.vod_seek_description
import dev.jamesrich.sofaking.ui.theme.SofaOverlayTrack
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource

/** The bar under the title for the remote: what has played, the breaks, and where a scrub would land. */
@Composable
internal fun VodSeekBar(positionMs: () -> Long, durationMs: Long?, scrubMs: Long?, markers: List<VodMarker>) {
    val played = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.tertiary
    val cursor = MaterialTheme.colorScheme.onSurface
    val length = durationMs?.takeIf { it > 0 }
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        drawSeekTrack(length?.let { positionMs().toFloat() / it } ?: 0f, length, markers, played, marker)
        if (length == null) return@Canvas
        scrubMs?.let { s ->
            val w = 4.dp.toPx()
            drawRect(
                cursor,
                Offset(s.coerceIn(0, length).toFloat() / length * size.width - w / 2, 0f),
                Size(w, size.height),
            )
        }
    }
}

/**
 * The seek bar for a finger or a mouse: Material's slider over the same track, so a drag shows where it would land
 * ([onScrub], which the times follow) and a release or a tap seeks there ([onSeek]).
 */
@Composable
internal fun VodSeekSlider(
    positionMs: () -> Long,
    lengthMs: Long,
    markers: List<VodMarker>,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val state = remember(lengthMs) {
        SliderState(value = positionMs().coerceIn(0, lengthMs).toFloat(), trackRange = 0f..lengthMs.toFloat())
    }
    val currentPositionMs by rememberUpdatedState(positionMs)
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { currentPositionMs() }.collect { if (!dragging) state.value = it.coerceIn(0, lengthMs).toFloat() }
    }
    val played = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.tertiary
    val description = stringResource(Res.string.vod_seek_description)
    Slider(
        state = state,
        onValueChange = {
            dragging = true
            state.value = it
            onScrub(it.toLong())
        },
        onValueChangeFinished = {
            dragging = false
            onSeek(state.value.toLong())
        },
        modifier = Modifier
            .fillMaxWidth()
            .tvFocus(MaterialTheme.shapes.small, focusedScale = 1f)
            .semantics { contentDescription = description },
        track = { s ->
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                drawSeekTrack(s.value / lengthMs, lengthMs, markers, played, marker)
            }
        },
    )
}

/** The seek bar's track: [playedFraction] of it filled, with the breaks marked over it. */
private fun DrawScope.drawSeekTrack(
    playedFraction: Float,
    lengthMs: Long?,
    markers: List<VodMarker>,
    played: Color,
    marker: Color,
) {
    val barHeight = 6.dp.toPx()
    val top = (size.height - barHeight) / 2
    val radius = CornerRadius(barHeight / 2)
    drawRoundRect(SofaOverlayTrack, Offset(0f, top), Size(size.width, barHeight), radius)
    if (lengthMs == null) return
    fun x(ms: Long) = (ms.coerceIn(0, lengthMs).toFloat() / lengthMs) * size.width
    drawRoundRect(played, Offset(0f, top), Size(playedFraction.coerceIn(0f, 1f) * size.width, barHeight), radius)
    markers.filter { it.kind == VodMarkerKind.BREAK }.forEach { m ->
        drawRect(
            marker.copy(alpha = 0.8f),
            Offset(x(m.startMs), top),
            Size((x(m.endMs) - x(m.startMs)).coerceAtLeast(2f), barHeight),
        )
    }
}
