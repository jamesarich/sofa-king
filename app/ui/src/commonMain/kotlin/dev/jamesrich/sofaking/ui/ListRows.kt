package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * Material's list item colors for a row in a list the D-pad walks (Settings, a program's airings, On later), with the
 * focused row's container raised to `surfaceVariant`: Material's focus state layer alone is too faint to read from the
 * sofa. A selected row keeps Material's selected container when [tintSelected], else it looks like any other row. An
 * unfocused row's container is [rest]: the page's surface, or clear over the picture.
 */
@Composable
internal fun listRowColors(
    interactions: InteractionSource,
    tintSelected: Boolean = true,
    rest: Color = MaterialTheme.colorScheme.surface,
): ListItemColors {
    val focused by interactions.collectIsFocusedAsState()
    val container = if (focused) MaterialTheme.colorScheme.surfaceVariant else rest
    return ListItemDefaults.colors(
        containerColor = container,
        selectedContainerColor = if (tintSelected) Color.Unspecified else container,
    )
}

/** One shape for a list row in every state, so the D-pad's focus ring keeps the row's outline. */
@Composable
internal fun listRowShapes(): ListItemShapes {
    val shape = MaterialTheme.shapes.medium
    return ListItemDefaults.shapes(
        shape = shape,
        selectedShape = shape,
        pressedShape = shape,
        focusedShape = shape,
        hoveredShape = shape,
        draggedShape = shape,
    )
}
