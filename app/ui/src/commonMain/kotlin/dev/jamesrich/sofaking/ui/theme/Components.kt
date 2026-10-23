package dev.jamesrich.sofaking.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** How much a focused control grows, as TV focus does; a guide cell passes 1 so it never covers its neighbours. */
const val TV_FOCUS_SCALE = 1.1f

/**
 * A D-pad focus cue for Material components, whose own focus state layer is too faint across a room: the control
 * scales by [focusedScale] on the theme's spatial spring and draws an outline ring in [shape] while it has focus.
 * The cue lives in a modifier node, so a focus move redraws the control without recomposing its caller.
 */
@Composable
fun Modifier.tvFocus(shape: Shape, focusedScale: Float = TV_FOCUS_SCALE): Modifier =
    this then TvFocusElement(
        shape = shape,
        focusedScale = focusedScale,
        ring = MaterialTheme.colorScheme.onSurface,
        spec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )

private data class TvFocusElement(
    val shape: Shape,
    val focusedScale: Float,
    val ring: Color,
    val spec: FiniteAnimationSpec<Float>,
) : ModifierNodeElement<TvFocusNode>() {
    override fun create() = TvFocusNode(shape, focusedScale, ring, spec)

    override fun update(node: TvFocusNode) = node.update(shape, focusedScale, ring, spec)

    override fun InspectorInfo.inspectableProperties() {
        name = "tvFocus"
        properties["shape"] = shape
        properties["focusedScale"] = focusedScale
    }
}

private class TvFocusNode(
    shape: Shape,
    private var focusedScale: Float,
    ring: Color,
    private var spec: FiniteAnimationSpec<Float>,
) : Modifier.Node(), FocusEventModifierNode, LayoutModifierNode, DrawModifierNode {
    private val scale = Animatable(1f)

    // Snapshot state, so a change redraws the layer that records the ring.
    private var focused by mutableStateOf(false)
    private var shape by mutableStateOf(shape)
    private var ring by mutableStateOf(ring)

    fun update(shape: Shape, focusedScale: Float, ring: Color, spec: FiniteAnimationSpec<Float>) {
        val rescale = focusedScale != this.focusedScale
        this.shape = shape
        this.focusedScale = focusedScale
        this.ring = ring
        this.spec = spec
        if (rescale) animate()
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus == focused) return
        focused = focusState.hasFocus
        animate()
    }

    private fun animate() {
        coroutineScope.launch { scale.animateTo(if (focused) focusedScale else 1f, spec) }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale.value
                scaleY = scale.value
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        // The layer placed in measure scales the ring with the content.
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        val outline = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
        translate(inset, inset) { drawOutline(outline, ring, style = Stroke(stroke)) }
    }
}

/**
 * A Material 3 button with the D-pad focus cue; every action button in the app is one. It rests tonal and fills
 * with the primary color only while focused, so a row of buttons shows at a glance which one OK will press.
 *
 * A disabled Material button cannot hold focus, so one turned off while focused (a press that starts a check, a wrong
 * PIN that empties its field) would drop focus to another row. This one keeps focus until the D-pad moves on: it
 * looks and reads as disabled and ignores presses, and only then leaves the focus order.
 */
@Composable
fun SofaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .tvFocus(ButtonDefaults.shape)
            .semantics { if (!enabled) disabled() },
        enabled = enabled || focused,
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                !enabled -> scheme.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA)
                focused -> scheme.primary
                else -> scheme.surfaceVariant
            },
            contentColor = when {
                !enabled -> scheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
                focused -> scheme.onPrimary
                else -> scheme.onSurface
            },
        ),
        interactionSource = interactions,
        content = content,
    )
}

// Material 3's disabled button colors, which a button kept focusable while disabled draws itself.
private const val DISABLED_CONTAINER_ALPHA = 0.12f
private const val DISABLED_CONTENT_ALPHA = 0.38f

/** A Material 3 filter chip in the label type, with the D-pad focus cue: the guide's filters and the library's choices. */
@Composable
internal fun SofaChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.tvFocus(FilterChipDefaults.shape),
    )
}
