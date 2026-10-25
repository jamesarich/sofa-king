package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.multiview_pick_hint
import dev.jamesrich.sofaking.resources.multiview_title
import dev.jamesrich.sofaking.resources.multiview_watch
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrimDense
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource

/** The picker's width; the picture plays on beside it. */
private val PICKER_WIDTH = 480.dp

/**
 * The channels to watch at once: [choices] as rows OK or a tap ticks, [picked] ticked, and Watch once two to four
 * are. The picture behind it plays on.
 */
@Composable
internal fun MultiviewPicker(
    choices: List<Channel>,
    picked: List<String>,
    onToggle: (Channel) -> Unit,
    onWatch: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(first) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .widthIn(max = PICKER_WIDTH)
                .background(SofaOverlayScrimDense)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.multiview_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.multiview_pick_hint), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onWatch,
                    enabled = picked.size in MULTIVIEW_MIN_TILES..Wire.MULTIVIEW_MAX_TILES,
                    modifier = Modifier.tvFocus(MaterialTheme.shapes.extraLarge),
                ) { Text(stringResource(Res.string.multiview_watch, picked.size)) }
            }
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(choices, key = { _, channel -> channel.guideId }) { index, channel ->
                    PickRow(
                        channel,
                        checked = channel.guideId in picked,
                        enabled = channel.guideId in picked || picked.size < Wire.MULTIVIEW_MAX_TILES,
                        onToggle = { onToggle(channel) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}

/** One channel as a row the whole of which OK or a tap ticks, so the D-pad never stops on a bare box. */
@Composable
private fun PickRow(
    channel: Channel,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.medium
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .tvFocus(shape, focusedScale = 1f)
            .clip(shape)
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = interactions,
                indication = ripple(),
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(
            containerColor = if (focused) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) { Text(channel.label(), style = MaterialTheme.typography.titleSmall) }
}
