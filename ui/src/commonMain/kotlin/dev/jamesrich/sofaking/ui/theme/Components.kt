package dev.jamesrich.sofaking.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** How much a focused control grows, as TV focus does; a guide cell passes 1 so it never covers its neighbours. */
const val TV_FOCUS_SCALE = 1.1f

/**
 * A D-pad focus cue for Material components, whose own focus state layer is too faint across a room: the control
 * scales by [focusedScale] on the theme's spatial spring and draws an outline ring in [shape] while it has focus.
 */
@Composable
fun Modifier.tvFocus(shape: Shape, focusedScale: Float = TV_FOCUS_SCALE): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "tvFocusScale",
    )
    val ring = if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent
    return this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(3.dp, ring, shape)
}

/** A Material 3 button with the D-pad focus cue; every action button in the app is one. */
@Composable
fun SofaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.tvFocus(ButtonDefaults.shape),
        enabled = enabled,
        content = content,
    )
}

/** A Material 3 switch with the D-pad focus cue. */
@Composable
fun SofaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier.tvFocus(CircleShape))
}
