package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.movedFavorite
import dev.jamesrich.sofaking.data.orderedFavorites
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.LogoMark
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource

/**
 * The channel manager behind Settings: the favorites in the order the Favorites filter shows them, with a row
 * per favorite that moves it, then every channel of the lineup with a switch that hides it on this device.
 */
@Composable
fun ChannelManagerScreen(
    channels: List<Channel>,
    favorites: Set<String>,
    favoritesOrder: List<String>,
    hidden: Set<String>,
    zapSkipsHidden: Boolean,
    onFavoritesOrderChanged: (List<String>) -> Unit,
    onChannelHiddenChanged: (guideId: String, hidden: Boolean) -> Unit,
    onZapSkipsHiddenChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(channels, favorites, favoritesOrder) { orderedFavorites(channels, favorites, favoritesOrder) }
    val orderIds = remember(ordered) { ordered.map { it.guideId } }
    val backFocus = remember { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(backFocus) }

    LazyColumn(
        modifier = modifier.fillMaxSize().screenPadding().focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenTitle(stringResource(Res.string.channels_title), onBack, backFocus) }
        item { SectionHeader(stringResource(Res.string.channels_section_favorites)) }
        item { HelpText(stringResource(if (ordered.isEmpty()) Res.string.channels_favorites_none else Res.string.channels_favorites_help)) }
        items(ordered, key = { "fav:${it.guideId}" }) { channel ->
            val index = orderIds.indexOf(channel.guideId)
            FavoriteOrderRow(
                channel = channel,
                canMoveUp = index > 0,
                canMoveDown = index < ordered.lastIndex,
                onMove = { step -> onFavoritesOrderChanged(movedFavorite(orderIds, channel.guideId, step)) },
            )
        }
        item { SectionHeader(stringResource(Res.string.channels_section_hidden)) }
        item { HelpText(stringResource(Res.string.channels_hidden_help)) }
        item {
            ToggleRow(
                label = stringResource(Res.string.channels_zap_skips),
                checked = zapSkipsHidden,
                onCheckedChange = onZapSkipsHiddenChanged,
            )
        }
        items(channels, key = { "ch:${it.guideId}" }) { channel ->
            ToggleRow(
                label = channel.label(),
                checked = channel.guideId !in hidden,
                onCheckedChange = { shown -> onChannelHiddenChanged(channel.guideId, !shown) },
            )
        }
    }
}

/** One favorite with its move buttons; a button that cannot move further is disabled, so focus sits on the other. */
@Composable
private fun FavoriteOrderRow(channel: Channel, canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit) {
    val label = channel.label()
    val upLabel = stringResource(Res.string.channels_move_up, label)
    val downLabel = stringResource(Res.string.channels_move_down, label)
    Row(
        modifier = Modifier.width(FIELD_WIDTH).focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChannelLogo(channel, 64.dp, 40.dp)
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        SofaButton(onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.semantics { contentDescription = upLabel }) { Text("▲") }
        SofaButton(onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.semantics { contentDescription = downLabel }) { Text("▼") }
    }
}

/** The list a startup channel is chosen from; OK on a row picks it and returns to Settings. */
@Composable
fun ChannelPickerScreen(
    channels: List<Channel>,
    selected: String?,
    onPick: (Channel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    BackHandler { onBack() }
    // The chosen channel takes focus when there is one, else Back, so the list opens where the user left it.
    LaunchedEffect(Unit) {
        if (selected == null || channels.none { it.guideId == selected } || !requestFocusWhenAttached(selectedFocus)) requestFocusWhenAttached(backFocus)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().screenPadding().focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenTitle(stringResource(Res.string.settings_startup), onBack, backFocus) }
        item { HelpText(stringResource(Res.string.settings_startup_pick_help)) }
        items(channels, key = { it.guideId }) { channel ->
            ChannelRow(
                channel = channel,
                selected = channel.guideId == selected,
                onClick = { onPick(channel) },
                modifier = if (channel.guideId == selected) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun ScreenTitle(title: String, onBack: () -> Unit, backFocus: FocusRequester) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LogoMark(40.dp)
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        SofaButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus)) { Text(stringResource(Res.string.settings_back)) }
    }
}

/** A channel as one focusable list row: logo, number and name, tinted while selected. */
@Composable
private fun ChannelRow(channel: Channel, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.medium
    ListItem(
        modifier = modifier
            .width(FIELD_WIDTH)
            .tvFocus(shape, focusedScale = 1f)
            .clip(shape)
            .clickable(interactionSource = interactions, indication = ripple(), role = Role.Button, onClick = onClick),
        leadingContent = { ChannelLogo(channel, 64.dp, 40.dp) },
        colors = ListItemDefaults.colors(
            containerColor = when {
                focused -> MaterialTheme.colorScheme.surfaceVariant
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) { Text(channel.label(), style = MaterialTheme.typography.titleSmall) }
}
