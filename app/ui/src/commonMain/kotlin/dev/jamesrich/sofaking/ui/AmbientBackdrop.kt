package dev.jamesrich.sofaking.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

/**
 * A full-bleed page background from the picture at [url]: its [ambient tint][rememberAmbientTint] fading down to the
 * theme's background, the picture itself blurred and faint over that, and a scrim so text over it reads. The picture is
 * decoded to [AMBIENT_SAMPLE_PX] and scaled up smooth, so it is soft even where `Modifier.blur` does nothing (Android
 * before 12). With no picture the tint gradient is the theme's background.
 */
@Composable
internal fun AmbientBackdrop(url: String?, modifier: Modifier = Modifier) {
    val page = MaterialTheme.colorScheme.background
    val tint = rememberAmbientTint(url)
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, page)))) {
        Crossfade(
            url,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "backdropCover",
        ) { cover ->
            if (cover != null) SoftCover(cover)
        }
        // The cover is faint and the scrim over it, so even an all-white cover leaves mauve text above AA contrast.
        Box(Modifier.fillMaxSize().background(page.copy(alpha = BACKDROP_SCRIM)))
    }
}

@Composable
private fun SoftCover(url: String) {
    val context = LocalPlatformContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context).data(url).size(AMBIENT_SAMPLE_PX, AMBIENT_SAMPLE_PX).cpuReadable().build()
    }
    AsyncImage(
        request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Low,
        modifier = Modifier.fillMaxSize().alpha(COVER_ALPHA).blur(COVER_BLUR),
    )
}

private val COVER_BLUR = 24.dp
private const val COVER_ALPHA = 0.25f
private const val BACKDROP_SCRIM = 0.6f
