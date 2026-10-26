package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.ui.listRowColors
import dev.jamesrich.sofaking.ui.listRowShapes
import dev.jamesrich.sofaking.ui.theme.tvFocus

/** The width of a settings control column: a field, a switch row or a choice row, so the page reads as one column. */
internal val FIELD_WIDTH = 640.dp

/** The gap between a settings page's rows: Material's list items already carry their own padding. */
internal val SETTINGS_ROW_GAP = 8.dp

/** How far clear of a settings page's edges focus keeps what it lands on, so the rows around it show. */
internal val SETTINGS_FOCUS_EDGE = 16.dp

/** A choice row with four or more segments. */
internal val WIDE_CHOICE_WIDTH = 820.dp

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun HelpText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(FIELD_WIDTH),
    )
}

/**
 * A label with its control(s) beside it, wrapping onto the next line where a phone is too narrow for them all; one
 * focus group so Left/Right stay inside the row.
 */
@Composable
internal fun SettingRow(label: String, controls: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        controls()
    }
}

/**
 * A switch setting as one list row, Material's toggleable list item: the whole row is the focus and touch target and
 * OK flips it, so the D-pad never stops on a bare switch. [help] reads beneath the label and with it.
 */
@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
) {
    val shapes = listRowShapes()
    val interactions = remember { MutableInteractionSource() }
    ListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.width(FIELD_WIDTH).tvFocus(shapes.shape, focusedScale = 1f),
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = help?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        shapes = shapes,
        // The switch shows the state, so an on row keeps the plain container.
        colors = listRowColors(interactions, tintSelected = false),
        interactionSource = interactions,
    ) { Text(label, style = MaterialTheme.typography.titleSmall) }
}

/**
 * A setting with a few named values, all on screen as connected segments; Left/Right move between them and OK picks
 * one. Up or Down into the row lands on the selected value. [help] reads beneath the segments.
 */
@Composable
internal fun <T> ChoiceRow(
    label: String,
    choices: List<T>,
    selected: T,
    choiceLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    help: String? = null,
    /** Wider than a field for four or more segments with longer names, so each name keeps one line. */
    width: Dp = FIELD_WIDTH,
) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        val selectedFocus = remember { FocusRequester() }
        // Where a name wraps (a phone), every segment takes the tallest one's height, so the row keeps one outline.
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .focusProperties { onEnter = { selectedFocus.requestFocus() } }
                .focusGroup(),
        ) {
            choices.forEachIndexed { index, choice ->
                val shape = SegmentedButtonDefaults.itemShape(index, choices.size)
                SegmentedButton(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    shape = shape,
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (choice == selected) Modifier.focusRequester(selectedFocus) else Modifier)
                        .tvFocus(shape, focusedScale = 1f),
                ) {
                    Text(choiceLabel(choice), textAlign = TextAlign.Center)
                }
            }
        }
        if (help != null) HelpText(help)
    }
}

/**
 * A setting's value as the page shows it: the [stored] one, and a pick at once while the store catches up. A new
 * stored value replaces it.
 */
@Composable
internal fun <T> rememberShown(stored: T): MutableState<T> = remember(stored) { mutableStateOf(stored) }

/** A [ToggleRow] for a stored setting: it flips at once and hands the new value to [onChange]. */
@Composable
internal fun SettingToggle(label: String, stored: Boolean, onChange: (Boolean) -> Unit, help: String? = null) {
    var checked by rememberShown(stored)
    ToggleRow(
        label = label,
        checked = checked,
        onCheckedChange = {
            checked = it
            onChange(it)
        },
        help = help,
    )
}

/** A [ChoiceRow] for a stored setting: the pick shows at once and goes to [onChange]. */
@Composable
internal fun <T> SettingChoice(
    label: String,
    stored: T,
    choices: List<T>,
    choiceLabel: @Composable (T) -> String,
    onChange: (T) -> Unit,
    help: String? = null,
) {
    var selected by rememberShown(stored)
    ChoiceRow(
        label = label,
        choices = choices,
        selected = selected,
        choiceLabel = choiceLabel,
        onSelect = {
            selected = it
            onChange(it)
        },
        help = help,
    )
}
