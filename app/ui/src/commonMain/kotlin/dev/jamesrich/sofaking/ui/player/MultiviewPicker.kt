package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.multiview_no_results
import dev.jamesrich.sofaking.resources.multiview_pick_hint
import dev.jamesrich.sofaking.resources.multiview_pick_keys_hint
import dev.jamesrich.sofaking.resources.multiview_search_hint
import dev.jamesrich.sofaking.resources.multiview_section_all
import dev.jamesrich.sofaking.resources.multiview_section_favorites
import dev.jamesrich.sofaking.resources.multiview_section_recent
import dev.jamesrich.sofaking.resources.multiview_section_results
import dev.jamesrich.sofaking.resources.multiview_section_watching
import dev.jamesrich.sofaking.resources.multiview_title
import dev.jamesrich.sofaking.resources.multiview_watch
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.listRowColors
import dev.jamesrich.sofaking.ui.listRowShapes
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.sectionHeading
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrimDense
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The picker's width; the panes play on beside it. */
internal val PICKER_WIDTH: Dp = 480.dp

/**
 * The channels to watch at once, beside the panes: a search box, then [groups] under their headings as rows OK or a
 * tap ticks, each with the program [airing] on it. A ticked channel ([picked]) starts playing at once; Watch, or
 * Right from a row, puts the list away ([onWatch]) once two to four are ticked.
 */
@Composable
internal fun MultiviewPicker(
    groups: List<PickGroup>,
    picked: List<String>,
    query: TextFieldState,
    airing: (Channel) -> String?,
    onToggle: (Channel) -> Unit,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    val search = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(first) }
    val ready = picked.size in MULTIVIEW_MIN_TILES..Wire.MULTIVIEW_MAX_TILES
    val keys = LocalLastInput.current.kind != InputKind.POINTER
    Column(
        modifier.fillMaxHeight().width(PICKER_WIDTH).background(SofaOverlayScrimDense).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.multiview_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.sectionHeading(),
        )
        val hint = stringResource(Res.string.multiview_pick_hint)
        val keysHint = if (keys) " " + stringResource(Res.string.multiview_pick_keys_hint) else ""
        Text(hint + keysHint, style = MaterialTheme.typography.bodyMedium)
        SofaButton(onClick = onWatch, main = true, enabled = ready) {
            Text(pluralStringResource(Res.plurals.multiview_watch, picked.size, picked.size))
        }
        TvTextField(
            state = query,
            focusRequester = search,
            imeAction = ImeAction.Done,
            onAction = { first.requestFocus(FocusDirection.Enter) },
            modifier = Modifier.fillMaxWidth(),
            hint = stringResource(Res.string.multiview_search_hint),
        )
        val rightWatches = Modifier.onPreviewKeyEvent { event ->
            val takes = ready && event.key == Key.DirectionRight
            if (takes && event.type == KeyEventType.KeyDown) onWatch()
            takes
        }
        LazyColumn(Modifier.fillMaxWidth().then(rightWatches), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEachIndexed { at, group ->
                item(key = group.section, contentType = "heading") { PickHeading(group) }
                itemsIndexed(
                    group.channels,
                    key = { _, channel -> channel.guideId },
                    contentType = { _, _ -> "channel" },
                ) { index, channel ->
                    PickRow(
                        channel,
                        program = airing(channel),
                        checked = channel.guideId in picked,
                        enabled = channel.guideId in picked || picked.size < Wire.MULTIVIEW_MAX_TILES,
                        onToggle = { onToggle(channel) },
                        modifier = if (at == 0 && index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}

/** A heading of the list, or that a search found nothing. */
@Composable
private fun PickHeading(group: PickGroup) {
    val text = if (group.channels.isEmpty()) Res.string.multiview_no_results else group.section.title
    Text(
        stringResource(text),
        Modifier.padding(top = 8.dp, bottom = 2.dp).sectionHeading(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val PickSection.title: StringResource
    get() = when (this) {
        PickSection.WATCHING -> Res.string.multiview_section_watching
        PickSection.FAVORITES -> Res.string.multiview_section_favorites
        PickSection.RECENT -> Res.string.multiview_section_recent
        PickSection.ALL -> Res.string.multiview_section_all
        PickSection.RESULTS -> Res.string.multiview_section_results
    }

/** One channel as a row the whole of which OK or a tap ticks, so the D-pad never stops on a bare box. */
@Composable
private fun PickRow(
    channel: Channel,
    program: String?,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shapes = listRowShapes()
    val interactions = remember { MutableInteractionSource() }
    ListItem(
        checked = checked,
        onCheckedChange = { onToggle() },
        modifier = modifier.fillMaxWidth().tvFocus(shapes.shape, focusedScale = 1f),
        enabled = enabled,
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        trailingContent = { ChannelLogo(channel, 48.dp, 30.dp) },
        supportingContent = program?.let { title -> { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        shapes = shapes,
        colors = listRowColors(interactions, tintSelected = false),
        interactionSource = interactions,
    ) { Text(channel.label(), style = MaterialTheme.typography.titleSmall, maxLines = 1) }
}
