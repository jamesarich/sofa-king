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
import androidx.compose.foundation.layout.Row
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
 * The library home while it loads: the spotlight's frame, the chip row and a placeholder row for Continue watching and
 * for the recent additions, each at the place and size the real one takes, so nothing moves when the page arrives.
 * Nothing in it takes focus, so the header keeps it.
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
            // The real spotlight card is the list's first item, under the list's top padding.
            SkeletonBlock(
                pulse,
                CardDefaults.shape,
                Modifier
                    .padding(top = HOME_TOP)
                    .padding(insets.horizontalOnly())
                    .fillMaxWidth()
                    .height(COMPACT_SPOTLIGHT_HEIGHT),
            )
        }
        Column(
            // The rows keep their real size and the window cuts them off, as it does the real shelves.
            Modifier.fillMaxWidth().weight(if (staysPut) 1f - SPOTLIGHT_HEIGHT_SHARE else 1f).clipToBounds()
                .wrapContentHeight(
                    Alignment.Top,
                    unbounded = true,
                ).padding(top = if (staysPut) HOME_TOP else HOME_GAP),
            verticalArrangement = Arrangement.spacedBy(HOME_GAP),
        ) {
            SkeletonChips(pulse, insets)
            skeletonStyles().forEach { style -> SkeletonShelf(style, pulse, insets) }
        }
    }
}

/** The chip row's place: Search and a library or two, each a chip's size inside the row's touch height. */
@Composable
private fun SkeletonChips(pulse: () -> Float, insets: PaddingValues) {
    Row(
        Modifier.padding(insets.horizontalOnly()).height(CHIP_ROW_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CHIP_WIDTHS.forEach { width ->
            SkeletonBlock(pulse, MaterialTheme.shapes.small, Modifier.width(width).height(CHIP_HEIGHT))
        }
    }
}

@Composable
private fun SkeletonShelf(style: CardStyle, pulse: () -> Float, insets: PaddingValues) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(
            pulse,
            MaterialTheme.shapes.extraSmall,
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
    val line = MaterialTheme.shapes.extraSmall
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

/** The library home's list: its top padding and the gap between its rows, which the skeleton keeps too. */
internal val HOME_TOP = 8.dp
internal val HOME_GAP = 28.dp

/** A chip is 32dp tall in a row as tall as Material's minimum touch target. */
private val CHIP_ROW_HEIGHT = 48.dp
private val CHIP_HEIGHT = 32.dp
private val CHIP_WIDTHS = listOf(76.dp, 96.dp, 116.dp)
internal const val POSTER_RATIO = 2f / 3f
private val TITLE_WIDTH = 160.dp
private val TITLE_HEIGHT = 18.dp
private val LINE_HEIGHT = 12.dp
private const val TITLE_LINE = 0.8f
private const val SUBTITLE_LINE = 0.5f
private const val SKELETON_CARDS = 12
private const val PULSE_LOW = 0.45f
private const val PULSE_HIGH = 0.9f
private const val PULSE_MS = 900
