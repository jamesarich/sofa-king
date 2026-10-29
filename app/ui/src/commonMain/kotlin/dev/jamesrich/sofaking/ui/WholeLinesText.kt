package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified

/**
 * [text] in as many whole lines of [style] as the height [modifier] leaves, the last ellipsized; nothing when not
 * even one fits. A fixed band never shows a line cut in half, at any font scale. [modifier] must give the band a
 * bounded height. The line count follows the band's measured height, so a new [text] in the same band composes
 * without subcomposing again.
 */
@Composable
internal fun WholeLinesText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val lineHeight = with(LocalDensity.current) {
        (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f).toPx()
    }
    var bandHeight by remember { mutableIntStateOf(0) }
    val lines = wholeLines(bandHeight, lineHeight)
    Box(modifier.onSizeChanged { bandHeight = it.height }) {
        if (lines > 0) {
            Text(text, style = style, color = color, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How many whole lines of [lineHeight] pixels fit in [bandHeight] pixels. */
internal fun wholeLines(bandHeight: Int, lineHeight: Float): Int =
    if (lineHeight > 0f) (bandHeight / lineHeight).toInt() else 0
