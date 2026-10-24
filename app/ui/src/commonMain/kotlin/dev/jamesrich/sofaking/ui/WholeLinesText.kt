package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified

/**
 * [text] in as many whole lines of [style] as the height [modifier] leaves, the last ellipsized; nothing when not
 * even one fits. A fixed band never shows a line cut in half, at any font scale.
 */
@Composable
internal fun WholeLinesText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    BoxWithConstraints(modifier) {
        val lineHeight = with(LocalDensity.current) {
            (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f).toPx()
        }
        val lines = if (constraints.hasBoundedHeight) (constraints.maxHeight / lineHeight).toInt() else Int.MAX_VALUE
        if (lines > 0) {
            Text(text, style = style, color = color, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}
