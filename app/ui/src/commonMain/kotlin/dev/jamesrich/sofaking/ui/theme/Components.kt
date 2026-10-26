package dev.jamesrich.sofaking.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.ui.LastInput
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.rememberIsTelevision
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.launch

/** How much a focused control grows, as TV focus does; a guide cell passes 1 so it never covers its neighbors. */
const val TV_FOCUS_SCALE = 1.1f

/**
 * A D-pad focus cue for Material components, whose own focus state layer is too faint across a room: the control
 * scales by [focusedScale] on the theme's spatial spring and draws an outline ring in [shape] while it has focus.
 * The cue lives in a modifier node, so a focus move redraws the control without recomposing its caller.
 *
 * On a TV, a mouse or a pointer remote's cursor moving over the control focuses it, so the cue shows what a click
 * will press and the D-pad carries on from there.
 */
@Composable
fun Modifier.tvFocus(shape: Shape, focusedScale: Float = TV_FOCUS_SCALE): Modifier = this then TvFocusElement(
    shape = shape,
    focusedScale = focusedScale,
    ring = MaterialTheme.colorScheme.onSurface,
    spec = MaterialTheme.motionScheme.fastSpatialSpec(),
    cursor = if (rememberIsTelevision()) LocalLastInput.current else null,
)

private data class TvFocusElement(
    val shape: Shape,
    val focusedScale: Float,
    val ring: Color,
    val spec: FiniteAnimationSpec<Float>,
    val cursor: LastInput?,
) : ModifierNodeElement<TvFocusNode>() {
    override fun create() = TvFocusNode(shape, focusedScale, ring, spec, cursor)

    override fun update(node: TvFocusNode) = node.update(shape, focusedScale, ring, spec, cursor)

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
    /** Where a TV's cursor last moved; null off a TV, where hovering focuses nothing. */
    private var cursor: LastInput?,
) : Modifier.Node(),
    FocusEventModifierNode,
    FocusRequesterModifierNode,
    PointerInputModifierNode,
    LayoutModifierNode,
    DrawModifierNode {
    private val scale = Animatable(1f)

    // Snapshot state, so a change redraws the layer that records the ring.
    private var focused by mutableStateOf(false)
    private var shape by mutableStateOf(shape)
    private var ring by mutableStateOf(ring)

    fun update(shape: Shape, focusedScale: Float, ring: Color, spec: FiniteAnimationSpec<Float>, cursor: LastInput?) {
        val rescale = focusedScale != this.focusedScale
        this.shape = shape
        this.focusedScale = focusedScale
        this.ring = ring
        this.spec = spec
        this.cursor = cursor
        if (rescale) animate()
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        val cursor = cursor ?: return
        if (pass != PointerEventPass.Main || focused) return
        if (pointerEvent.type != PointerEventType.Enter && pointerEvent.type != PointerEventType.Move) return
        // Only a cursor the viewer is moving: one left resting where a D-pad scroll brings a card under it stays put.
        if (pointerEvent.changes.none { it.type == PointerType.Mouse }) return
        if (uptimeMs() - cursor.cursorMovedAtMs > CURSOR_MOVE_WINDOW_MS) return
        requestFocus()
    }

    override fun onCancelPointerInput() = Unit

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

/** How recently the cursor must have moved for a hover to take focus. */
private const val CURSOR_MOVE_WINDOW_MS = 250L

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

/**
 * A Material 3 filter chip in the label type, with the D-pad focus cue: the guide's filters and the library's choices.
 */
@Composable
internal fun SofaChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.tvFocus(FilterChipDefaults.shape),
    )
}

/**
 * A Material 3 assist chip in the label type, with the D-pad focus cue: a chip that acts or goes somewhere rather than
 * filtering, such as the guide's Now, a library to open or a cast member to search for.
 */
@Composable
internal fun SofaActionChip(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.tvFocus(AssistChipDefaults.shape),
    )
}
