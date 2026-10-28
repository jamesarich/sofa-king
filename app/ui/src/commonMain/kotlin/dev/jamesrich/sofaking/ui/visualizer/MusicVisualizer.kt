package dev.jamesrich.sofaking.ui.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import dev.jamesrich.sofaking.visualizer.Presets
import dev.jamesrich.sofaking.visualizer.Visualizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A MilkDrop-style visualizer of what [tap] hears, filling its box. The next of the built-in presets takes over when
 * [song] changes and every [PRESET_MS] after that, the old picture melting into the new one.
 */
@Composable
internal fun MusicVisualizer(tap: AudioAnalyzer, song: String?, modifier: Modifier = Modifier) {
    val bitmap = remember { PixelBitmap(WIDTH, HEIGHT) }
    val visualizer = remember { Visualizer(WIDTH, HEIGHT, Presets.all.first()) }
    var preset by remember { mutableIntStateOf(0) }
    LaunchedEffect(song) { preset++ }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PRESET_MS)
            preset++
        }
    }
    LaunchedEffect(tap, visualizer) {
        var applied = 0
        var last = 0L
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            if (last != 0L && now - last < FRAME_NS) continue
            val dt = if (last == 0L) FRAME_SECONDS else (now - last) / NANOS_PER_SECOND
            last = now
            if (preset != applied) {
                applied = preset
                visualizer.preset = Presets.all[applied % Presets.all.size]
            }
            val audio = tap.frame(dt)
            bitmap.set(withContext(Dispatchers.Default) { visualizer.render(audio, dt) })
        }
    }
    Canvas(modifier) {
        drawImage(
            bitmap.image,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

/** The picture is drawn this small and scaled up: the look is soft by nature, and a phone or TV CPU keeps up. */
private const val WIDTH = 256
private const val HEIGHT = 144

private const val FRAME_NS = 30_000_000L
private const val FRAME_SECONDS = 0.033
private const val NANOS_PER_SECOND = 1e9
private const val PRESET_MS = 45_000L
