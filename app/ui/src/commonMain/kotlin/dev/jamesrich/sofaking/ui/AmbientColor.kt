package dev.jamesrich.sofaking.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.flow.first
import kotlin.math.pow

/** The side, in pixels, of the square a picture is drawn into to find its color, or decoded to behind a cover. */
internal const val AMBIENT_SAMPLE_PX = 32

/**
 * The relative luminance an ambient tint never exceeds. The theme's mauve text reads at least 7:1 over a color this
 * dark and its dusk text at least 11:1, so the tint changes the page's mood without costing the contrast Color.kt
 * documents.
 */
internal const val MAX_TINT_LUMINANCE = 0.03f

/** The most saturated an ambient tint is; a vivid cover would otherwise turn the whole page neon. */
internal const val MAX_TINT_SATURATION = 0.7f

private const val OPAQUE_ALPHA = 128
private const val BUCKET_BITS = 4
private const val BUCKETS = 1 shl (BUCKET_BITS * 3)
private const val VIBRANCY_WEIGHT = 3f
private const val CHANNEL_MAX = 255f
private const val CHANNEL_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val ALPHA_SHIFT = 24

/**
 * The color the [pixels] (ARGB) are mostly of: the busiest of the 4096 quantized colors, with the more saturated ones
 * weighing up to four times a gray one, averaged over its own pixels. Mostly transparent pixels are ignored; null when
 * nothing opaque is left.
 */
internal fun dominantColor(pixels: IntArray): Color? {
    val weight = FloatArray(BUCKETS)
    val red = LongArray(BUCKETS)
    val green = LongArray(BUCKETS)
    val blue = LongArray(BUCKETS)
    val count = IntArray(BUCKETS)
    for (argb in pixels) {
        if ((argb ushr ALPHA_SHIFT) < OPAQUE_ALPHA) continue
        val r = (argb shr RED_SHIFT) and CHANNEL_MASK
        val g = (argb shr GREEN_SHIFT) and CHANNEL_MASK
        val b = argb and CHANNEL_MASK
        val bucket = (bucketOf(r) shl (BUCKET_BITS * 2)) or (bucketOf(g) shl BUCKET_BITS) or bucketOf(b)
        val chroma = (maxOf(r, g, b) - minOf(r, g, b)) / CHANNEL_MAX
        weight[bucket] += 1f + VIBRANCY_WEIGHT * chroma
        red[bucket] += r
        green[bucket] += g
        blue[bucket] += b
        count[bucket]++
    }
    var best = -1
    for (bucket in 0 until BUCKETS) {
        if (count[bucket] > 0 && (best < 0 || weight[bucket] > weight[best])) best = bucket
    }
    if (best < 0) return null
    val n = count[best]
    return Color(red = (red[best] / n).toInt(), green = (green[best] / n).toInt(), blue = (blue[best] / n).toInt())
}

private fun bucketOf(channel: Int) = channel shr (Byte.SIZE_BITS - BUCKET_BITS)

/**
 * [source] as a page tint: hue kept, saturation (of the linear channels) capped at [MAX_TINT_SATURATION] and
 * brightness taken down until its luminance is at most [MAX_TINT_LUMINANCE].
 */
internal fun ambientTint(source: Color): Color {
    val r = toLinear(source.red)
    val g = toLinear(source.green)
    val b = toLinear(source.blue)
    val high = maxOf(r, g, b)
    val saturation = if (high == 0f) 0f else (high - minOf(r, g, b)) / high
    // Pulling the channels toward the brightest by the same factor lowers saturation without moving the hue.
    val keep = if (saturation > MAX_TINT_SATURATION) MAX_TINT_SATURATION / saturation else 1f
    val lr = high + (r - high) * keep
    val lg = high + (g - high) * keep
    val lb = high + (b - high) * keep
    val luminance = LUMA_R * lr + LUMA_G * lg + LUMA_B * lb
    val scale = if (luminance > MAX_TINT_LUMINANCE) MAX_TINT_LUMINANCE / luminance else 1f
    return Color(toSrgb(lr * scale), toSrgb(lg * scale), toSrgb(lb * scale))
}

private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f

private const val SRGB_LINEAR_CUTOFF = 0.04045f
private const val LINEAR_CUTOFF = 0.0031308f
private const val SRGB_LINEAR_SLOPE = 12.92f
private const val SRGB_OFFSET = 0.055f
private const val SRGB_SCALE = 1.055f
private const val SRGB_GAMMA = 2.4
private const val FLARE = 0.05f

/** The sRGB channel [c] as linear light. */
internal fun toLinear(c: Float): Float = if (c <= SRGB_LINEAR_CUTOFF) {
    c / SRGB_LINEAR_SLOPE
} else {
    ((c + SRGB_OFFSET) / SRGB_SCALE).toDouble().pow(SRGB_GAMMA).toFloat()
}

private fun toSrgb(c: Float): Float = if (c <= LINEAR_CUTOFF) {
    c * SRGB_LINEAR_SLOPE
} else {
    (SRGB_SCALE * c.toDouble().pow(1 / SRGB_GAMMA) - SRGB_OFFSET).toFloat()
}

/** The WCAG contrast ratio between two opaque colors. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = luminanceOf(a)
    val lb = luminanceOf(b)
    return (maxOf(la, lb) + FLARE) / (minOf(la, lb) + FLARE)
}

private fun luminanceOf(c: Color) = LUMA_R * toLinear(c.red) + LUMA_G * toLinear(c.green) + LUMA_B * toLinear(c.blue)

/** Colors found per picture address, oldest dropped past [capacity]; [Color.Unspecified] marks a picture with none. */
internal class AmbientColorCache(private val capacity: Int) {
    private val colors = LinkedHashMap<String, Color>()

    operator fun get(url: String): Color? = colors[url]

    operator fun set(url: String, color: Color) {
        colors.remove(url)
        colors[url] = color
        while (colors.size > capacity) colors.remove(colors.keys.first())
    }
}

private val ambientColors = AmbientColorCache(capacity = 64)

/**
 * The page tint for the picture at [url], faded in when it is found; the theme's background while there is no picture,
 * it loads or has no color. The fade follows the platform's animation scale, so with animations off the tint snaps.
 */
@Composable
internal fun rememberAmbientTint(url: String?): Color {
    val background = MaterialTheme.colorScheme.background
    var found by remember(url) { mutableStateOf(url?.let { ambientColors[it] }) }
    if (url != null && found == null) {
        AmbientSampler(url) {
            ambientColors[url] = it
            found = it
        }
    }
    val target = found?.takeIf { it.isSpecified } ?: background
    val tint by animateColorAsState(target, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "ambientTint")
    return tint
}

/** Loads the picture at [url] small, finds its tint and reports it once; reports [Color.Unspecified] when it fails. */
@Composable
private fun AmbientSampler(url: String, onFind: (Color) -> Unit) {
    val context = LocalPlatformContext.current
    val request = remember(url, context) {
        // Hardware bitmaps cannot be drawn into the software bitmap the pixels are read from.
        ImageRequest.Builder(context).data(url).size(AMBIENT_SAMPLE_PX, AMBIENT_SAMPLE_PX).cpuReadable().build()
    }
    val painter = rememberAsyncImagePainter(request)
    val report by rememberUpdatedState(onFind)
    LaunchedEffect(painter) {
        val state = painter.state.first { it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error }
        val color = if (state is AsyncImagePainter.State.Success) {
            runCatchingCancellable { samplePixels(painter) }.getOrNull()?.let(::dominantColor)?.let(::ambientTint)
        } else {
            null
        }
        report(color ?: Color.Unspecified)
    }
}

private fun samplePixels(painter: Painter): IntArray {
    val bitmap = ImageBitmap(AMBIENT_SAMPLE_PX, AMBIENT_SAMPLE_PX)
    val size = Size(AMBIENT_SAMPLE_PX.toFloat(), AMBIENT_SAMPLE_PX.toFloat())
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
        with(painter) { draw(size) }
    }
    return IntArray(AMBIENT_SAMPLE_PX * AMBIENT_SAMPLE_PX).also { bitmap.readPixels(it) }
}
