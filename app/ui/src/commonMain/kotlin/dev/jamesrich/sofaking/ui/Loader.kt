package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The brand's busy indicator: Material 3 Expressive's morphing loader in the theme's primary amber. Decorative; pair it
 * with a text label.
 */
@Composable
fun SofaLoader(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    LoadingIndicator(modifier = modifier.size(size))
}

/**
 * A small [SofaLoader] with [label] on one line beside it. Where the space left is too narrow for the whole label (a TV
 * header with every tab shown), only the loader shows and the label stays its accessible description, so the text never
 * wraps a letter per line.
 */
@Composable
fun SofaLoaderLabel(label: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Layout(
        content = {
            SofaLoader(size = size)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        },
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = label },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val loader = measurables[0].measure(loose)
        val text = measurables[1].measure(loose.copy(maxWidth = Constraints.Infinity))
        val gap = 8.dp.roundToPx()
        val withText = loader.width + gap + text.width <= constraints.maxWidth
        val width = if (withText) loader.width + gap + text.width else loader.width
        val height = if (withText) maxOf(loader.height, text.height) else loader.height
        layout(width, height) {
            loader.placeRelative(0, (height - loader.height) / 2)
            if (withText) text.placeRelative(loader.width + gap, (height - text.height) / 2)
        }
    }
}
