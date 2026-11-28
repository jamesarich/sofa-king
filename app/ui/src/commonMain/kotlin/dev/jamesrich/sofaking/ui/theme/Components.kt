package dev.jamesrich.sofaking.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LastInput
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.arrowKeysMoveFocus
import dev.jamesrich.sofaking.ui.rememberIsTelevision
import dev.jamesrich.sofaking.ui.rememberMotionEnabled
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How much a focused control grows, as TV focus does; a guide cell passes 1 so it never covers its neighbors. */
const val TV_FOCUS_SCALE = 1.1f

/** How thick the focus ring is: thick enough to find from a sofa, drawn inside the control's bounds. */
internal val TV_FOCUS_RING_WIDTH = 3.dp

/**
 * A D-pad focus cue for Material components, whose own focus state layer is too faint across a room: the control
 * scales by [focusedScale] on the theme's spatial spring and draws an outline ring in [shape] while it has focus.
 * The cue lives in a modifier node, so a focus move redraws the control without recomposing its caller.
 *
 * On a TV, a mouse or a pointer remote's cursor moving over the control focuses it, so the cue shows what a click
 * will press and the D-pad carries on from there. Off a TV the cue shows only while the last input was a key, as the
 * web's `:focus-visible` does: a click focuses what it pressed, and a ring left on it would only mark the last click.
 */
@Composable
fun Modifier.tvFocus(shape: Shape, focusedScale: Float = TV_FOCUS_SCALE): Modifier {
    val tv = rememberIsTelevision()
    val lastInput = LocalLastInput.current
    return this then TvFocusElement(
        shape = shape,
        focusedScale = focusedScale,
        ring = MaterialTheme.colorScheme.onSurface,
        spec = MaterialTheme.motionScheme.fastSpatialSpec(),
        cursor = if (tv) lastInput else null,
        keys = if (tv) null else lastInput,
        animates = rememberMotionEnabled(),
    )
}

private data class TvFocusElement(
    val shape: Shape,
    val focusedScale: Float,
    val ring: Color,
    val spec: FiniteAnimationSpec<Float>,
    val cursor: LastInput?,
    val keys: LastInput?,
    val animates: Boolean,
) : ModifierNodeElement<TvFocusNode>() {
    override fun create() = TvFocusNode(shape, focusedScale, ring, spec, cursor, keys, animates)

    override fun update(node: TvFocusNode) = node.update(shape, focusedScale, ring, spec, cursor, keys, animates)

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
    /** The last input off a TV, where the cue shows only after a key; null on a TV, where it always shows. */
    keys: LastInput?,
    /** False where the system's animations are off: the scale then snaps. */
    private var animates: Boolean,
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
    private var keys by mutableStateOf(keys)

    /** Whether focus is shown now; it reads [LastInput.kind], so the cue comes and goes with the input. */
    private val cued: Boolean get() = focusCueShown(focused, keys?.kind)

    fun update(
        shape: Shape,
        focusedScale: Float,
        ring: Color,
        spec: FiniteAnimationSpec<Float>,
        cursor: LastInput?,
        keys: LastInput?,
        animates: Boolean,
    ) {
        this.keys = keys
        val rescale = focusedScale != this.focusedScale
        this.animates = animates
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
        val target = if (focused) focusedScale else 1f
        coroutineScope.launch { if (animates) scale.animateTo(target, spec) else scale.snapTo(target) }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val shown = if (focusCueShown(true, keys?.kind)) scale.value else 1f
                scaleX = shown
                scaleY = shown
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!cued) return
        // The layer placed in measure scales the ring with the content.
        val stroke = TV_FOCUS_RING_WIDTH.toPx()
        val inset = stroke / 2
        val outline = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
        translate(inset, inset) { drawOutline(outline, ring, style = Stroke(stroke)) }
    }
}

/**
 * Whether a control shows its focus cue: while focused, on a TV ([lastInput] null) always, elsewhere only while the
 * last input was a key.
 */
internal fun focusCueShown(focused: Boolean, lastInput: InputKind?): Boolean = focused && lastInput != InputKind.POINTER

/** How recently the cursor must have moved for a hover to take focus. */
private const val CURSOR_MOVE_WINDOW_MS = 250L

/**
 * A Material control with the [tvFocus] cue, its ring hugging the shape the control draws. A Material button, chip or
 * icon button pads itself out to the 48dp minimum touch target inside its own modifier, so a ring outside it would
 * trace the padding, a few dp off the control. This pads to the same minimum outside the cue instead, centering the
 * control as Material does, and [control] lays out without its own.
 */
@Composable
fun TvFocusControl(
    shape: Shape,
    modifier: Modifier = Modifier,
    focusedScale: Float = TV_FOCUS_SCALE,
    control: @Composable (Modifier) -> Unit,
) {
    val minimum = LocalMinimumInteractiveComponentSize.current
    val ringed = modifier
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val least = if (minimum.isSpecified) minimum.roundToPx() else 0
            val width = maxOf(placeable.width, least)
            val height = maxOf(placeable.height, least)
            layout(width, height) {
                // Rounded as Material centers it, so the control lands on the same pixel.
                placeable.place(
                    ((width - placeable.width) / 2f).roundToInt(),
                    ((height - placeable.height) / 2f).roundToInt(),
                )
            }
        }
        .tvFocus(shape, focusedScale)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) { control(ringed) }
}

/**
 * A Material 3 button with the D-pad focus cue; every action button in the app is one. It follows Material's button
 * hierarchy: the screen's [main] action (Play, Watch, Unlock) rests filled in the primary color, as Material's common
 * button is, and the others rest in Material's tonal colors. The focused button turns white, as androidx.tv.material3's
 * Button does, so a row of buttons shows at a glance which one OK will press, even beside a filled one.
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
    main: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    TvFocusControl(ButtonDefaults.shape, modifier) { ringed ->
        Button(
            onClick = { if (enabled) onClick() },
            modifier = ringed.semantics { if (!enabled) disabled() },
            enabled = enabled || focused,
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    !enabled -> scheme.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA)
                    focused -> scheme.onSurface
                    main -> scheme.primary
                    else -> scheme.secondaryContainer
                },
                contentColor = when {
                    !enabled -> scheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
                    focused -> scheme.inverseOnSurface
                    main -> scheme.onPrimary
                    else -> scheme.onSecondaryContainer
                },
            ),
            interactionSource = interactions,
            content = content,
        )
    }
}

// Material 3's disabled button colors, which a button kept focusable while disabled draws itself.
private const val DISABLED_CONTAINER_ALPHA = 0.12f
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * A Material 3 filter chip in the label type, with the D-pad focus cue: the guide's filters and the library's choices.
 * [count] follows the label, dimmer, so "News 4" reads as News with four channels rather than a name; [icon] leads
 * an unselected chip that acts (Search), and a check leads a selected one.
 */
@Composable
internal fun SofaChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    icon: ImageVector? = null,
) {
    TvFocusControl(FilterChipDefaults.shape, modifier) { ringed ->
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { ChipLabel(label, count) },
            modifier = ringed,
            // A check says selected without relying on the chip's color.
            leadingIcon = chipIcon(if (selected) Icons.Filled.Check else icon, FilterChipDefaults.IconSize),
        )
    }
}

/**
 * A Material 3 assist chip in the label type, with the D-pad focus cue: a chip that acts or goes somewhere rather than
 * filtering, such as the guide's Now, a library to open or a cast member to search for. [icon] leads it, as Material's
 * assist chips do, so an action stands apart from the filters beside it; [count] follows the label, dimmer.
 */
@Composable
internal fun SofaActionChip(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    count: Int? = null,
) {
    TvFocusControl(AssistChipDefaults.shape, modifier) { ringed ->
        AssistChip(
            onClick = onClick,
            label = { ChipLabel(label, count) },
            modifier = ringed,
            leadingIcon = chipIcon(icon, AssistChipDefaults.IconSize),
        )
    }
}

/** A chip's words and, set apart in a dimmer tone of the chip's own color, how many it holds. */
@Composable
private fun ChipLabel(label: String, count: Int?) {
    val style = MaterialTheme.typography.labelLarge
    if (count == null) {
        Text(label, style = style)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(CHIP_COUNT_GAP)) {
        Text(label, style = style)
        Text(count.toString(), style = style, color = LocalContentColor.current.copy(alpha = CHIP_COUNT_ALPHA))
    }
}

private fun chipIcon(icon: ImageVector?, size: Dp): (@Composable () -> Unit)? = icon?.let {
    { Icon(it, contentDescription = null, modifier = Modifier.size(size)) }
}

/** How far a chip's count sits from its label, and how much of the label's color it keeps. */
private val CHIP_COUNT_GAP = 4.dp
private const val CHIP_COUNT_ALPHA = 0.7f

/**
 * A chip that opens a menu of [options] and says which one is chosen, as Plex's filter bar does, so a long list (a
 * library's genres) costs one chip instead of a row of them. [label] is the chosen option's words; picking an entry
 * closes the menu and hands focus back to the chip, which keeps its place while the list behind it reloads. The menu
 * opens on the chosen entry, so a D-pad's first press lands where the viewer already is. [focusRequester] reaches
 * the chip, for a row that restores focus to it.
 */
@Composable
internal fun <T> SofaMenuChip(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(modifier) {
        TvFocusControl(AssistChipDefaults.shape, Modifier.focusRequester(focusRequester)) { ringed ->
            AssistChip(
                onClick = { open = true },
                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                modifier = ringed.semantics { role = Role.DropdownList },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.arrowKeysMoveFocus()) {
            val chosen = remember { FocusRequester() }
            options.forEach { option ->
                val isChosen = option == selected
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                        scope.launch { requestFocusWhenAttached(focusRequester) }
                    },
                    trailingIcon = if (isChosen) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = if (isChosen) Modifier.focusRequester(chosen) else Modifier,
                )
            }
            LaunchedEffect(Unit) { requestFocusWhenAttached(chosen) }
        }
    }
}

/**
 * A Material 3 tonal icon toggle with the D-pad focus cue, for a yes-or-no action a page offers beside its main one
 * (watched, on the watchlist) where a text button would take a line of its own on a phone. [label] names the action
 * for a screen reader and for the tooltip a mouse hover or a long touch shows; the toggle's state is [checked], read
 * as a filled button, so the label stays the action's name.
 */
@Composable
internal fun SofaIconToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier.tvFocus(IconButtonDefaults.filledShape),
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** A tonal icon button with [SofaIconToggle]'s tooltip and D-pad focus cue, for a one-shot action. */
@Composable
internal fun SofaIconAction(onClick: () -> Unit, icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledTonalIconButton(onClick = onClick, modifier = modifier.tvFocus(IconButtonDefaults.filledShape)) {
            Icon(icon, contentDescription = label)
        }
    }
}
