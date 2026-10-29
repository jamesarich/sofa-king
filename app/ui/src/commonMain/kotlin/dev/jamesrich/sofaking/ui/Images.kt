package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.jamesrich.sofaking.data.mediaHttpClient
import dev.jamesrich.sofaking.ui.theme.SofaIcons

/**
 * Coil's loader for channel logos and program images, over the API's connection pool. Coil's own memory cache,
 * disk cache (not on the web) and decoding to the slot's size do the rest, so logos are on screen at a cold
 * start without the network and a guide full of them holds no full-size bitmaps.
 */
internal fun sofaKingImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
    .components { add(KtorNetworkFetcherFactory(httpClient = { mediaHttpClient })) }
    .crossfade(CROSSFADE_MS)
    .build()

private const val CROSSFADE_MS = 150

/** The request for [url] that fades the picture in, or shows it at once while the system's animations are off. */
@Composable
internal fun rememberFadingRequest(url: String): ImageRequest {
    val context = LocalPlatformContext.current
    val animates = rememberMotionEnabled()
    return remember(url, animates, context) {
        ImageRequest.Builder(context).data(url).crossfade(if (animates) CROSSFADE_MS else 0).build()
    }
}

/**
 * The picture filling its card. Under it, and alone while it loads or when it fails, [label] stands in its place,
 * or the library mark when the card's own caption already names the item. [hidden] blurs and dims the picture, for
 * Hide spoilers; Android before 12 has no blur, so there it is only dimmed.
 */
@Composable
internal fun BoxScope.Artwork(url: String?, label: String?, hidden: Boolean = false) {
    if (label == null) {
        Icon(
            SofaIcons.VideoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.Center).size(48.dp),
        )
    } else {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(12.dp),
        )
    }
    if (url == null) return
    AsyncImage(
        rememberFadingRequest(url),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = if (hidden) Modifier.fillMaxSize().blur(HIDDEN_BLUR) else Modifier.fillMaxSize(),
    )
    if (hidden) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = HIDDEN_DIM)),
        )
    }
}

private val HIDDEN_BLUR = 16.dp
private const val HIDDEN_DIM = 0.6f

/** The 16:9 frame of a TV picture, and of the artwork cut to fit one. */
internal const val WIDESCREEN = 16f / 9f
