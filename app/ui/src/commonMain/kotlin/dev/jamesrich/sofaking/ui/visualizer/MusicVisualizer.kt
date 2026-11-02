package dev.jamesrich.sofaking.ui.visualizer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import dev.jamesrich.sofaking.visualizer.MilkPreset
import dev.jamesrich.sofaking.visualizer.Presets
import dev.jamesrich.sofaking.visualizer.Visualizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A MilkDrop-style visualizer of what [tap] hears, filling its box. It opens on the look called [startLook] (the one
 * shown last) and moves to the next when [song] changes, when [skips] counts up and every [PRESET_MS] after that, the
 * old picture melting into the new one; [onLook] hears each look's name once it shows. The looks are the built-in
 * presets, then the classic pack in a shuffled order once it has loaded. Each look is named over the picture for a few
 * seconds, and with it the song's [title] and [artist] when [showSong].
 */
@Composable
internal fun MusicVisualizer(
    tap: AudioAnalyzer,
    song: String?,
    skips: Int,
    startLook: String?,
    onLook: (String) -> Unit,
    title: String,
    artist: String?,
    showSong: Boolean,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(tap) {
        tap.want(true)
        onDispose { tap.want(false) }
    }

    val bitmap = remember { PixelBitmap(WIDTH, HEIGHT) }
    val visualizer = remember { Visualizer(WIDTH, HEIGHT, Presets.all.first()) }
    val looks by produceState(Presets.all) { value = Presets.all + ClassicPack.presets().shuffled() }
    val currentLooks by rememberUpdatedState(looks)
    var wanted by remember { mutableStateOf(startLook ?: Presets.all.first().name) }
    // Until the pack loads, a look from it that was shown last is stood in for by the first built-in.
    val found = looks.firstOrNull { it.name == wanted }
    val shown = found ?: looks.first()
    val currentShown by rememberUpdatedState(shown)
    val currentOnLook by rememberUpdatedState(onLook)
    val opening = remember { booleanArrayOf(true) }
    LaunchedEffect(song, skips) {
        if (opening[0]) opening[0] = false else wanted = nextLook(currentLooks, wanted)
        while (true) {
            delay(PRESET_MS)
            wanted = nextLook(currentLooks, wanted)
        }
    }
    LaunchedEffect(wanted, found != null) { if (found != null) currentOnLook(wanted) }
    LaunchedEffect(tap, visualizer) {
        var applied: MilkPreset? = null
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L && now - last < FRAME_NS) continue
            val dt = if (last == 0L) FRAME_SECONDS else (now - last) / NANOS_PER_SECOND
            last = now
            if (applied !== currentShown) {
                applied = currentShown
                visualizer.preset = currentShown
            }
            val audio = tap.frame(dt)
            bitmap.set(withContext(Dispatchers.Default) { visualizer.render(audio, dt) })
        }
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawImage(
                bitmap.image,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }
        LookCaption(
            key = song to shown.name,
            look = shown.name,
            title = title.takeIf { showSong },
            artist = artist.takeIf { showSong },
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/** The look after the one called [current] (the first, when [current] is not among [looks]), wrapping round. */
internal fun nextLook(looks: List<MilkPreset>, current: String): String {
    val at = looks.indexOfFirst { it.name == current }
    return looks[(at + 1) % looks.size].name
}

/** Names the look, and the song when [title] is set, over the picture: fades in, holds, fades out, for each [key]. */
@Composable
private fun LookCaption(key: Any, look: String, title: String?, artist: String?, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(key) {
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(CAPTION_FADE_IN_MS))
        delay(CAPTION_HOLD_MS)
        alpha.animateTo(0f, tween(CAPTION_FADE_OUT_MS))
    }
    val shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 12f)
    Column(modifier.screenPadding().graphicsLayer { this.alpha = alpha.value }) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.headlineMedium.copy(shadow = shadow),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        artist?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium.copy(shadow = shadow),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            look,
            Modifier.padding(top = if (title != null) 8.dp else 0.dp),
            style = MaterialTheme.typography.labelLarge.copy(shadow = shadow),
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
private const val CAPTION_FADE_IN_MS = 600
private const val CAPTION_HOLD_MS = 4_000L
private const val CAPTION_FADE_OUT_MS = 1_500
