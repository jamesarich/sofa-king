package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.ui.theme.tvFocus

/** The width of a settings control column: a field, a switch row or a choice row, so the page reads as one column. */
internal val FIELD_WIDTH = 640.dp

/** A choice row with four or more segments. */
internal val WIDE_CHOICE_WIDTH = 820.dp

@Composable
internal fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun HelpText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(FIELD_WIDTH))
}

/** A label with its control(s) beside it; one focus group so Left/Right stay inside the row. */
@Composable
internal fun SettingRow(label: String, controls: @Composable () -> Unit) {
    Row(
        modifier = Modifier.focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        controls()
    }
}

/**
 * A switch setting as one list row: the whole row is the focus and touch target and OK flips it, so the D-pad never
 * stops on a bare switch. [help] reads beneath the label and with it.
 */
@Composable
internal fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, help: String? = null, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.medium
    ListItem(
        modifier = modifier
            .width(FIELD_WIDTH)
            .tvFocus(shape, focusedScale = 1f)
            .clip(shape)
            .toggleable(
                value = checked,
                interactionSource = interactions,
                indication = ripple(),
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = help?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        colors = ListItemDefaults.colors(
            containerColor = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
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
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
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
                        .then(if (choice == selected) Modifier.focusRequester(selectedFocus) else Modifier)
                        .tvFocus(shape, focusedScale = 1f),
                ) {
                    Text(choiceLabel(choice))
                }
            }
        }
        if (help != null) HelpText(help)
    }
}
