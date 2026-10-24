package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.CaptionStyle
import kotlin.math.roundToInt

internal enum class CaptionEdge { NONE, OUTLINE, DROP_SHADOW, RAISED, DEPRESSED }

/** How captions look: the viewer's system caption settings, or the app's large high-contrast style. */
@Immutable
internal data class CaptionAppearance(
    val foreground: Color,
    val background: Color,
    val window: Color,
    val edge: CaptionEdge,
    val edgeColor: Color,
    val fontFamily: FontFamily?,
    val bold: Boolean,
    val scale: Float,
    /** Colors and styles carried in the stream; off for the high-contrast style so its box always shows. */
    val embeddedStyles: Boolean,
) {
    companion object {
        /** White on black, what the platform shows when the viewer has set nothing. */
        val DEFAULT =
            CaptionAppearance(
                Color.White, Color.Black, Color.Transparent, CaptionEdge.NONE, Color.White, null, false, 1f, true,
            )
        val LARGE_HIGH_CONTRAST = DEFAULT.copy(scale = 1.3f, embeddedStyles = false)
    }
}

/** The viewer's system caption settings, or null where the platform has none or they are off. */
@Composable
internal expect fun rememberSystemCaptionAppearance(): CaptionAppearance?

@Composable
internal fun rememberCaptionAppearance(style: CaptionStyle): CaptionAppearance {
    val system = rememberSystemCaptionAppearance()
    return remember(style, system) {
        when (style) {
            CaptionStyle.LARGE_HIGH_CONTRAST -> CaptionAppearance.LARGE_HIGH_CONTRAST
            CaptionStyle.SYSTEM -> system ?: CaptionAppearance.DEFAULT
        }
    }
}

/**
 * Where a cue goes, by the rules Media3's subtitle painter applies: [CaptionCue.line] counts text lines from
 * the top (0 and up) or the bottom (-1 and down) for [CaptionCue.LINE_TYPE_NUMBER], or is a fraction of the
 * height; [CaptionCue.position] is a fraction of the width at the cue's anchor. An unset line sits at the bottom.
 */
internal object CaptionGeometry {
    /** Caption text size as a fraction of the video height, Media3's default. */
    const val TEXT_SIZE_FRACTION = 0.0533f

    /** Line pitch as a multiple of the text size. */
    const val LINE_SPACING = 1.2f

    fun left(position: Float, positionAnchor: Int, textWidth: Int, parentWidth: Int): Int {
        if (position == CaptionCue.DIMEN_UNSET) return (parentWidth - textWidth) / 2
        val anchor = position * parentWidth
        val left = when (positionAnchor) {
            CaptionCue.ANCHOR_TYPE_END -> anchor - textWidth
            CaptionCue.ANCHOR_TYPE_MIDDLE -> anchor - textWidth / 2f
            else -> anchor
        }
        return left.roundToInt().coerceIn(0, (parentWidth - textWidth).coerceAtLeast(0))
    }

    fun top(line: Float, lineType: Int, lineAnchor: Int, lineHeight: Float, textHeight: Int, parentHeight: Int): Int {
        val top = when {
            line == CaptionCue.DIMEN_UNSET -> (parentHeight - textHeight).toFloat()

            lineType == CaptionCue.LINE_TYPE_NUMBER && line >= 0 -> line * lineHeight

            lineType == CaptionCue.LINE_TYPE_NUMBER -> parentHeight + (line + 1) * lineHeight - textHeight

            else -> {
                val anchor = line * parentHeight
                when (lineAnchor) {
                    CaptionCue.ANCHOR_TYPE_END -> anchor - textHeight
                    CaptionCue.ANCHOR_TYPE_MIDDLE -> anchor - textHeight / 2f
                    else -> anchor
                }
            }
        }
        return top.roundToInt().coerceIn(0, (parentHeight - textHeight).coerceAtLeast(0))
    }
}

/**
 * Text captions drawn in Compose: each cue at its 608/708 row and column, in [style]'s appearance, clear of the
 * overscan band ([bottomFraction] of the height) at the top and bottom.
 */
@Composable
internal fun Captions(player: LivePlayer, style: CaptionStyle, bottomFraction: Float, modifier: Modifier = Modifier) {
    val appearance = rememberCaptionAppearance(style)
    val cues by player.cues.collectAsStateWithLifecycle()
    if (cues.isEmpty()) return
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        // The overscan band is kept clear at the top as well as the bottom.
        val inset = (constraints.maxHeight * bottomFraction).roundToInt()
        val parentHeight = constraints.maxHeight - 2 * inset
        val parentWidth = constraints.maxWidth
        val fontPx = constraints.maxHeight * CaptionGeometry.TEXT_SIZE_FRACTION * appearance.scale
        val lineHeightPx = fontPx * CaptionGeometry.LINE_SPACING
        val textStyle = with(density) {
            TextStyle(
                color = appearance.foreground,
                fontSize = fontPx.toSp(),
                lineHeight = lineHeightPx.toSp(),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                fontFamily = appearance.fontFamily ?: FontFamily.SansSerif,
                fontWeight = if (appearance.bold) FontWeight.Bold else null,
                shadow = edgeShadow(appearance, fontPx),
            )
        }
        for (cue in cues) {
            Layout(
                content = { CueText(cueString(cue.text, appearance), textStyle, appearance, fontPx) },
            ) { measurables, constraints ->
                val placeable = measurables.first().measure(
                    Constraints(maxWidth = parentWidth, maxHeight = parentHeight),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        CaptionGeometry.left(cue.position, cue.positionAnchor, placeable.width, parentWidth),
                        inset + CaptionGeometry.top(
                            cue.line,
                            cue.lineType,
                            cue.lineAnchor,
                            lineHeightPx,
                            placeable.height,
                            parentHeight,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CueText(text: AnnotatedString, style: TextStyle, appearance: CaptionAppearance, fontPx: Float) {
    Box(if (appearance.window.alpha > 0f) Modifier.background(appearance.window) else Modifier) {
        if (appearance.edge == CaptionEdge.OUTLINE) {
            // The stroke pass sits under the fill pass, so the outline only shows around the glyphs.
            Text(text, style = style.copy(color = appearance.edgeColor, drawStyle = Stroke(width = fontPx / 8f)))
        }
        Text(text, style = style)
    }
}

private fun edgeShadow(appearance: CaptionAppearance, fontPx: Float): Shadow? {
    val d = fontPx / 16f
    return when (appearance.edge) {
        CaptionEdge.DROP_SHADOW -> Shadow(appearance.edgeColor, Offset(d, d), blurRadius = d)
        CaptionEdge.RAISED -> Shadow(appearance.edgeColor, Offset(-d / 2, -d / 2), blurRadius = d / 2)
        CaptionEdge.DEPRESSED -> Shadow(appearance.edgeColor, Offset(d / 2, d / 2), blurRadius = d / 2)
        else -> null
    }
}

/**
 * The cue's text with a padding space on both ends of every line and the background behind each line,
 * as a TV caption box; the stream's own styles are kept only when [CaptionAppearance.embeddedStyles].
 */
internal fun cueString(text: AnnotatedString, appearance: CaptionAppearance): AnnotatedString = buildAnnotatedString {
    val lines = text.text.split('\n')
    var sourceOffset = 0
    lines.forEachIndexed { index, line ->
        if (index > 0) append('\n')
        if (line.isBlank()) {
            // A blank line keeps its row but gets no box, or 608's leading newline draws an empty tab.
            sourceOffset += line.length + 1
            return@forEachIndexed
        }
        val lineStart = length
        append(PAD)
        val textStart = length
        append(line)
        append(PAD)
        // The box goes on first so a background color carried in the stream draws over it.
        addStyle(SpanStyle(background = appearance.background), lineStart, length)
        if (appearance.embeddedStyles) {
            val from = sourceOffset
            val to = sourceOffset + line.length
            for (span in text.spanStyles) {
                val start = span.start.coerceAtLeast(from)
                val end = span.end.coerceAtMost(to)
                if (start < end) addStyle(span.item, start - from + textStart, end - from + textStart)
            }
        }
        sourceOffset += line.length + 1
    }
}

private const val PAD = ' '
