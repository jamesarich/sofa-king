package dev.jamesrich.sofaking.ui.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_spotlight_play
import dev.jamesrich.sofaking.resources.library_spotlight_resume
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.theme.SofaButton
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.coroutineContext

/** The spotlight takes this share of the page's height where it stays above the shelves. */
internal const val SPOTLIGHT_HEIGHT_SHARE = 0.4f

/**
 * Whether the spotlight stays above the shelves and follows focus (a TV, or a window with room), rather than scrolling
 * away as the list's first card (a phone, or a window too short for it).
 */
internal val LayoutProfile.spotlightStaysPut: Boolean get() = tv || (!compactWidth && !compactHeight)

/**
 * The backdrop, title, tagline and (off a TV) a Resume or Play button of [item]; [item] changes crossfade. It is never
 * a focus target on a TV: it only draws what [item] says, and the shelves' cards choose [item]. [onPlay] is null on a
 * TV, which has the cards and its Play key.
 */
@Composable
internal fun Spotlight(
    item: MediaItem?,
    onPlay: ((MediaItem) -> Unit)?,
    insets: PaddingValues,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val motion = rememberMotionEnabled()
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.inverseSurface)) {
        Crossfade(
            targetState = item,
            animationSpec = if (motion) tween(FADE_MS) else snap(),
            modifier = Modifier.fillMaxSize(),
            label = "spotlight",
        ) { shown ->
            if (shown != null) SpotlightContent(shown, onPlay, insets, compact)
        }
    }
}

@Composable
private fun SpotlightContent(item: MediaItem, onPlay: ((MediaItem) -> Unit)?, insets: PaddingValues, compact: Boolean) {
    val art = item.backdrop ?: item.poster
    // A poster stands in for a missing backdrop, blurred so its grain does not read as a picture.
    val artModifier = if (item.backdrop == null) Modifier.fillMaxSize().blur(POSTER_BLUR) else Modifier.fillMaxSize()
    Box(Modifier.fillMaxSize()) {
        if (art != null) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            AsyncImage(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = artModifier)
            // Dark scrims keep the text readable on any artwork in both themes.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, SCRIM_BOTTOM))))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(SCRIM_START, Color.Transparent))))
        }
        val ink = if (art != null) Color.White else MaterialTheme.colorScheme.inverseOnSurface
        val padding = if (compact) {
            Modifier.padding(
                16.dp,
            )
        } else {
            Modifier.padding(insets.horizontalOnly()).padding(bottom = 20.dp)
        }
        Column(
            Modifier.align(Alignment.BottomStart).widthIn(max = TEXT_MAX_WIDTH).then(padding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                item.spotlightTitle(),
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            spotlightSubtitle(item)?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 1)
            }
            spotlightTagline(item)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onPlay != null && item.kind.playable) {
                val label = if (resumes(
                        item,
                    )
                ) {
                    Res.string.library_spotlight_resume
                } else {
                    Res.string.library_spotlight_play
                }
                SofaButton(onClick = { onPlay(item) }, main = true, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(label))
                }
            }
        }
    }
}

private fun MediaItem.spotlightTitle(): String = if (kind == ItemKind.EPISODE) showTitle ?: title else title

/** An episode's number and title, otherwise the year; null when there is neither. */
internal fun spotlightSubtitle(item: MediaItem): String? = if (item.kind == ItemKind.EPISODE) {
    listOfNotNull(item.episodeLabel, item.title.takeIf { item.showTitle != null }).joinToString(" · ").ifEmpty { null }
} else {
    item.year?.toString()
}

/** The remote's Info key runs [onInfo] as it comes up, for a card whose OK plays and whose page is one key away. */
internal fun Modifier.infoKey(onInfo: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val info = event.key == Key.Info
    if (info && event.type == KeyEventType.KeyUp) onInfo()
    info
}

/** False when the system turned animations off, so a crossfade or shimmer shows its end state at once. */
@Composable
internal fun rememberMotionEnabled(): Boolean {
    var enabled by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { enabled = coroutineContext[MotionDurationScale]?.scaleFactor != 0f }
    return enabled
}

private const val FADE_MS = 400
private val POSTER_BLUR = 24.dp
private val TEXT_MAX_WIDTH = 640.dp
private val SCRIM_BOTTOM = Color.Black.copy(alpha = 0.85f)
private val SCRIM_START = Color.Black.copy(alpha = 0.6f)
