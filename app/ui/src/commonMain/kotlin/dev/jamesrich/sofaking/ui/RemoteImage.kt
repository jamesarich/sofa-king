package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.roundToIntSize
import dev.jamesrich.sofaking.data.ImageFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.roundToInt

/**
 * Decoded images by URL and size, touched only from the composition's thread. The newest [MAX_IMAGES] stay; a
 * URL that failed is not fetched again for [RETRY_FAILED_MS], so a lineup of missing logos does not refetch on every scroll.
 */
private object ImageMemory {
    private const val MAX_IMAGES = 96
    private const val RETRY_FAILED_MS = 5 * 60_000L
    private val images = LinkedHashMap<String, ImageBitmap>()
    private val failedAt = HashMap<String, Long>()

    fun get(key: String): ImageBitmap? = images.remove(key)?.also { images[key] = it }

    fun shouldFetch(key: String): Boolean = failedAt[key]?.let { uptimeMs() - it >= RETRY_FAILED_MS } ?: true

    fun put(key: String, image: ImageBitmap?) {
        if (image == null) {
            failedAt[key] = uptimeMs()
            return
        }
        failedAt.remove(key)
        images[key] = image
        if (images.size > MAX_IMAGES) images.remove(images.keys.first())
    }
}

/**
 * An image from [url] in a [width] by [height] slot, fitted by [contentScale]; the slot stays empty while it loads
 * or when it fails. Decoding and scaling to the slot's pixels run off the main thread, so a guide full of logos
 * neither stalls scrolling nor holds full-size bitmaps. Decorative.
 */
@Composable
internal fun RemoteImage(url: String, width: Dp, height: Dp, contentScale: ContentScale, modifier: Modifier) {
    val slot = with(LocalDensity.current) { Size(width.toPx(), height.toPx()).roundToIntSize() }
    val key = "$url@${slot.width}x${slot.height}"
    val image by produceState(ImageMemory.get(key), key) {
        if (value == null && ImageMemory.shouldFetch(key)) {
            val decoded = ImageFetch.bytes(url)?.let { bytes ->
                withContext(Dispatchers.Default) { runCatching { bytes.decodeToImageBitmap().scaledToCover(slot) }.getOrNull() }
            }
            ImageMemory.put(key, decoded)
            value = decoded
        }
    }
    val loaded = image
    if (loaded == null) {
        Box(modifier)
    } else {
        Image(loaded, contentDescription = null, contentScale = contentScale, modifier = modifier)
    }
}

/** This image shrunk, keeping its aspect ratio, until it just covers [slot]; an image already that small is returned as is. */
private fun ImageBitmap.scaledToCover(slot: IntSize): ImageBitmap {
    if (slot.width <= 0 || slot.height <= 0) return this
    val scale = maxOf(slot.width.toFloat() / width, slot.height.toFloat() / height)
    if (scale >= 1f) return this
    val target = IntSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    val scaled = ImageBitmap(target.width, target.height)
    Canvas(scaled).drawImageRect(
        image = this,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(width, height),
        dstOffset = IntOffset.Zero,
        dstSize = target,
        paint = Paint().apply { filterQuality = FilterQuality.Medium },
    )
    return scaled
}
