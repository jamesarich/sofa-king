package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.jamesrich.sofaking.data.ImageFetch
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Decoded images by URL, touched only from the composition's thread. The newest [MAX_IMAGES] stay; a URL that
 * failed is not fetched again for [RETRY_FAILED_MS], so a lineup of missing logos does not refetch on every scroll.
 */
private object ImageMemory {
    private const val MAX_IMAGES = 96
    private const val RETRY_FAILED_MS = 5 * 60_000L
    private val images = LinkedHashMap<String, ImageBitmap>()
    private val failedAt = HashMap<String, Long>()

    fun get(url: String): ImageBitmap? = images.remove(url)?.also { images[url] = it }

    fun shouldFetch(url: String): Boolean = failedAt[url]?.let { uptimeMs() - it >= RETRY_FAILED_MS } ?: true

    fun put(url: String, image: ImageBitmap?) {
        if (image == null) {
            failedAt[url] = uptimeMs()
            return
        }
        failedAt.remove(url)
        images[url] = image
        if (images.size > MAX_IMAGES) images.remove(images.keys.first())
    }
}

/** An image from [url], fitted by [contentScale]; [modifier]'s box stays empty while it loads or when it fails. Decorative. */
@Composable
internal fun RemoteImage(url: String, contentScale: ContentScale, modifier: Modifier) {
    val image by produceState(ImageMemory.get(url), url) {
        if (value == null && ImageMemory.shouldFetch(url)) {
            val decoded = ImageFetch.bytes(url)?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
            ImageMemory.put(url, decoded)
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
