package dev.jamesrich.sofaking.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import dev.jamesrich.sofaking.ui.theme.SofaAmber

/** Cookie, clover, soft pentagon, back to cookie: the shapes the loader morphs through, normalized to 0..1. */
private val LoaderShapes: List<RoundedPolygon> = listOf(
    RoundedPolygon.star(8, radius = 1f, innerRadius = 0.78f, rounding = CornerRounding(0.18f)),
    RoundedPolygon.star(4, radius = 1f, innerRadius = 0.52f, rounding = CornerRounding(0.42f)),
    RoundedPolygon.star(5, radius = 1f, innerRadius = 0.86f, rounding = CornerRounding(0.3f)),
).map { it.normalized() }

private val LoaderMorphs: List<Morph> = LoaderShapes.indices.map { Morph(LoaderShapes[it], LoaderShapes[(it + 1) % LoaderShapes.size]) }

private const val MORPH_MS = 650
private const val SPIN_MS = 4_500

/**
 * The brand's busy indicator: an amber shape that morphs and turns. Decorative; pair it with a text label.
 * The animated values are read only in the draw phase, so it never recomposes while it spins.
 */
@Composable
fun SofaLoader(modifier: Modifier = Modifier, size: Dp = 48.dp, color: Color = SofaAmber) {
    val transition = rememberInfiniteTransition(label = "loader")
    val step = transition.animateFloat(
        initialValue = 0f,
        targetValue = LoaderMorphs.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(MORPH_MS * LoaderMorphs.size, easing = LinearEasing), RepeatMode.Restart),
        label = "morph",
    )
    val spin = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val androidPath = remember { android.graphics.Path() }
    val path = remember(androidPath) { androidPath.asComposePath() }
    Canvas(modifier.size(size)) {
        val s = step.value
        val index = s.toInt().coerceIn(0, LoaderMorphs.lastIndex)
        // Each morph eases in and out on its own, so the shape rests briefly at every corner of the cycle.
        val progress = FastOutSlowInEasing.transform(s - index)
        androidPath.rewind()
        LoaderMorphs[index].toPath(progress, androidPath)
        rotate(spin.value + progress * 90f) {
            scale(this.size.width, this.size.height, pivot = Offset.Zero) {
                drawPath(path, color)
            }
        }
    }
}
