package dev.jamesrich.sofaking.ui.library

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_loading
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.WIDESCREEN
import dev.jamesrich.sofaking.ui.screenInsets
import org.jetbrains.compose.resources.stringResource

/**
 * The library home while it loads: the spotlight's frame and a placeholder row for Continue watching and for the
 * recent additions, each card at the size the real one takes. Nothing in it takes focus, so the header keeps it.
 */
@Composable
internal fun LibrarySkeleton(modifier: Modifier = Modifier) {
    val insets = screenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues()
    val strength = rememberSkeletonPulse()
    val pulse = { strength.value }
    val staysPut = LocalLayoutProfile.current.spotlightStaysPut
    val loading = stringResource(Res.string.library_loading)
    Column(modifier.fillMaxSize().semantics { contentDescription = loading }) {
        if (staysPut) {
            SkeletonBlock(pulse, RectangleShape, Modifier.fillMaxWidth().weight(SPOTLIGHT_HEIGHT_SHARE))
        } else {
            SkeletonBlock(
                pulse,
                CardDefaults.shape,
                Modifier.padding(insets.horizontalOnly()).fillMaxWidth().height(COMPACT_SPOTLIGHT_HEIGHT),
            )
        }
        Column(
            // The rows keep their real size and the window cuts them off, as it does the real shelves.
            Modifier.fillMaxWidth().weight(if (staysPut) 1f - SPOTLIGHT_HEIGHT_SHARE else 1f).clipToBounds()
                .wrapContentHeight(Alignment.Top, unbounded = true).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            skeletonStyles().forEach { style -> SkeletonShelf(style, pulse, insets) }
        }
    }
}

@Composable
private fun SkeletonShelf(style: CardStyle, pulse: () -> Float, insets: PaddingValues) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(
            pulse,
            RoundedCornerShape(LINE_RADIUS),
            Modifier.padding(insets.horizontalOnly()).width(TITLE_WIDTH).height(TITLE_HEIGHT),
        )
        LazyRow(
            userScrollEnabled = false,
            contentPadding = insets.horizontalOnly(),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            items(List(SKELETON_CARDS) { it }) { SkeletonCard(style, pulse) }
        }
    }
}

/** A card's picture at [style]'s size, and the two lines of its label. */
@Composable
private fun SkeletonCard(style: CardStyle, pulse: () -> Float) {
    val (width, ratio) = skeletonCardSize(style)
    val line = RoundedCornerShape(LINE_RADIUS)
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SkeletonBlock(pulse, CardDefaults.shape, Modifier.fillMaxWidth().aspectRatio(ratio))
        SkeletonBlock(pulse, line, Modifier.fillMaxWidth(TITLE_LINE).height(LINE_HEIGHT))
        SkeletonBlock(pulse, line, Modifier.fillMaxWidth(SUBTITLE_LINE).height(LINE_HEIGHT))
    }
}

/** A card's width and picture ratio, the same constants the real cards use. */
internal fun skeletonCardSize(style: CardStyle): Pair<Dp, Float> = when (style) {
    CardStyle.POSTER -> POSTER_WIDTH to POSTER_RATIO
    CardStyle.WIDE -> WIDE_WIDTH to WIDESCREEN
    CardStyle.SQUARE -> SQUARE_WIDTH to 1f
}

@Composable
private fun SkeletonBlock(pulse: () -> Float, shape: Shape, modifier: Modifier = Modifier) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).graphicsLayer { alpha = pulse() })
}

/** A slow fade between two strengths; fixed at the first when the system turned animations off. */
@Composable
private fun rememberSkeletonPulse(): State<Float> {
    if (!rememberMotionEnabled()) return remember { mutableFloatStateOf(PULSE_HIGH) }
    return rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = PULSE_LOW,
        targetValue = PULSE_HIGH,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "pulse",
    )
}

internal val COMPACT_SPOTLIGHT_HEIGHT = 220.dp
internal const val POSTER_RATIO = 2f / 3f
private val LINE_RADIUS = 4.dp
private val TITLE_WIDTH = 160.dp
private val TITLE_HEIGHT = 18.dp
private val LINE_HEIGHT = 12.dp
private const val TITLE_LINE = 0.8f
private const val SUBTITLE_LINE = 0.5f
private const val SKELETON_CARDS = 12
private const val PULSE_LOW = 0.45f
private const val PULSE_HIGH = 0.9f
private const val PULSE_MS = 900
