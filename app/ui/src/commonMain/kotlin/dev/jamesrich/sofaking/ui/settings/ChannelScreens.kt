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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.ChannelGroup
import dev.jamesrich.sofaking.data.channelGroups
import dev.jamesrich.sofaking.data.movedFavorite
import dev.jamesrich.sofaking.data.orderedFavorites
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.channels_favorites_help
import dev.jamesrich.sofaking.resources.channels_favorites_none
import dev.jamesrich.sofaking.resources.channels_group_collapse
import dev.jamesrich.sofaking.resources.channels_group_expand
import dev.jamesrich.sofaking.resources.channels_group_shown
import dev.jamesrich.sofaking.resources.channels_hidden_help
import dev.jamesrich.sofaking.resources.channels_move_down
import dev.jamesrich.sofaking.resources.channels_move_up
import dev.jamesrich.sofaking.resources.channels_section_favorites
import dev.jamesrich.sofaking.resources.channels_section_hidden
import dev.jamesrich.sofaking.resources.channels_title
import dev.jamesrich.sofaking.resources.channels_zap_skips
import dev.jamesrich.sofaking.resources.settings_back
import dev.jamesrich.sofaking.resources.settings_startup
import dev.jamesrich.sofaking.resources.settings_startup_pick_help
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.LogoMark
import dev.jamesrich.sofaking.ui.ScrollOnlyWhenHidden
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource

/**
 * The channel manager behind Settings: the favorites in the order the Favorites filter shows them, with a row
 * per favorite that moves it, then the lineup's groups, each with a switch that hides or shows all of its
 * channels on this device and a button that opens them as switches of their own.
 */
@Composable
fun ChannelManagerScreen(
    channels: List<Channel>,
    favorites: Set<String>,
    favoritesOrder: List<String>,
    hidden: Set<String>,
    zapSkipsHidden: Boolean,
    onFavoritesOrderChange: (List<String>) -> Unit,
    onChannelHiddenChange: (guideId: String, hidden: Boolean) -> Unit,
    /** A group's switch: every channel of it hidden or shown in one write. */
    onChannelsHiddenChange: (guideIds: Collection<String>, hidden: Boolean) -> Unit,
    onZapSkipsHiddenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered =
        remember(channels, favorites, favoritesOrder) { orderedFavorites(channels, favorites, favoritesOrder) }
    val orderIds = remember(ordered) { ordered.map { it.guideId } }
    val groups = remember(channels, hidden) { channelGroups(channels, hidden) }
    // The groups opened to their channels; a visit starts with all of them closed, so the list is short.
    val expanded = remember { mutableStateListOf<String>() }
    val backFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val firstFocus = if (showsBackButton()) backFocus else contentFocus
    BackHandler { onBack() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(firstFocus) }

    // Focus scrolls only to show what it lands on, so the first row taking focus keeps the title on screen.
    ScrollOnlyWhenHidden(SETTINGS_FOCUS_EDGE) {
        LazyColumn(
            modifier = modifier.fillMaxSize().screenPadding().focusRequester(contentFocus).focusRestorer().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { ScreenTitle(stringResource(Res.string.channels_title), onBack, backFocus) }
            favoritesSection(ordered, orderIds, onFavoritesOrderChange)
            hiddenSection(
                groups,
                expanded,
                hidden,
                zapSkipsHidden,
                onZapSkipsHiddenChange,
                onChannelsHiddenChange,
                onChannelHiddenChange,
            )
        }
    }
}

/** The favorites in the order the Favorites filter shows them, each with a row that moves it. */
private fun LazyListScope.favoritesSection(
    ordered: List<Channel>,
    orderIds: List<String>,
    onFavoritesOrderChange: (List<String>) -> Unit,
) {
    item { SectionHeader(stringResource(Res.string.channels_section_favorites)) }
    item {
        HelpText(
            stringResource(
                if (ordered.isEmpty()) {
                    Res.string.channels_favorites_none
                } else {
                    Res.string.channels_favorites_help
                },
            ),
        )
    }
    items(ordered, key = { "fav:${it.guideId}" }) { channel ->
        val index = orderIds.indexOf(channel.guideId)
        FavoriteOrderRow(
            channel = channel,
            canMoveUp = index > 0,
            canMoveDown = index < ordered.lastIndex,
            onMove = { step -> onFavoritesOrderChange(movedFavorite(orderIds, channel.guideId, step)) },
        )
    }
}

/** Zap skipping, then the lineup's groups, each a switch for all its channels, opened to a switch per channel. */
private fun LazyListScope.hiddenSection(
    groups: List<ChannelGroup>,
    expanded: SnapshotStateList<String>,
    hidden: Set<String>,
    zapSkipsHidden: Boolean,
    onZapSkipsHiddenChange: (Boolean) -> Unit,
    onChannelsHiddenChange: (guideIds: Collection<String>, hidden: Boolean) -> Unit,
    onChannelHiddenChange: (guideId: String, hidden: Boolean) -> Unit,
) {
    item { SectionHeader(stringResource(Res.string.channels_section_hidden)) }
    item { HelpText(stringResource(Res.string.channels_hidden_help)) }
    item {
        ToggleRow(
            label = stringResource(Res.string.channels_zap_skips),
            checked = zapSkipsHidden,
            onCheckedChange = onZapSkipsHiddenChange,
        )
    }
    for (group in groups) {
        val open = group.name in expanded
        item(key = "group:${group.name}") {
            GroupRow(
                group = group,
                expanded = open,
                onShownChange = { shown -> onChannelsHiddenChange(group.channels.map { it.guideId }, !shown) },
                onExpandedChange = { if (it) expanded.add(group.name) else expanded.remove(group.name) },
            )
        }
        if (open) {
            items(group.channels, key = { "ch:${group.name}:${it.guideId}" }) { channel ->
                ToggleRow(
                    label = channel.label(),
                    checked = channel.guideId !in hidden,
                    onCheckedChange = { shown -> onChannelHiddenChange(channel.guideId, !shown) },
                    modifier = Modifier.padding(start = 32.dp),
                )
            }
        }
    }
}

/**
 * A group as one row: its switch row (OK shows or hides every channel of it; the line beneath counts what is
 * shown) and, beside it in the same focus group, the button that opens or closes its channels.
 */
@Composable
private fun GroupRow(
    group: ChannelGroup,
    expanded: Boolean,
    onShownChange: (Boolean) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    val toggleLabel =
        stringResource(
            if (expanded) Res.string.channels_group_collapse else Res.string.channels_group_expand,
            group.name,
        )
    Row(
        modifier = Modifier.focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToggleRow(
            label = group.name,
            help = stringResource(Res.string.channels_group_shown, group.shown, group.total),
            checked = group.anyShown,
            onCheckedChange = onShownChange,
        )
        SofaButton(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.semantics {
                contentDescription =
                    toggleLabel
            },
        ) {
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        }
    }
}

/** One favorite with its move buttons; a move that reaches the end of the list hands focus to the other button. */
@Composable
private fun FavoriteOrderRow(channel: Channel, canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit) {
    val label = channel.label()
    val upLabel = stringResource(Res.string.channels_move_up, label)
    val downLabel = stringResource(Res.string.channels_move_down, label)
    val upFocus = remember { FocusRequester() }
    val downFocus = remember { FocusRequester() }
    var upFocused by remember { mutableStateOf(false) }
    var downFocused by remember { mutableStateOf(false) }
    LaunchedEffect(canMoveUp) { if (!canMoveUp && upFocused) downFocus.requestFocus() }
    LaunchedEffect(canMoveDown) { if (!canMoveDown && downFocused) upFocus.requestFocus() }
    Row(
        modifier = Modifier.width(FIELD_WIDTH).focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChannelLogo(channel, 64.dp, 40.dp)
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        SofaButton(
            onClick = { onMove(-1) },
            enabled = canMoveUp,
            modifier = Modifier.focusRequester(upFocus).onFocusChanged { upFocused = it.isFocused }.semantics {
                contentDescription =
                    upLabel
            },
        ) { Icon(Icons.Filled.KeyboardArrowUp, null) }
        SofaButton(
            onClick = { onMove(1) },
            enabled = canMoveDown,
            modifier = Modifier.focusRequester(downFocus).onFocusChanged { downFocused = it.isFocused }.semantics {
                contentDescription =
                    downLabel
            },
        ) { Icon(Icons.Filled.KeyboardArrowDown, null) }
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
    val contentFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    val firstFocus = if (showsBackButton()) backFocus else contentFocus
    BackHandler { onBack() }
    // The chosen channel takes focus when there is one, else Back (or the first channel where Back isn't drawn), so
    // the list opens where the user left it.
    LaunchedEffect(Unit) {
        if (selected == null || channels.none { it.guideId == selected } || !requestFocusWhenAttached(selectedFocus)) {
            requestFocusWhenAttached(firstFocus)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().screenPadding().focusRequester(contentFocus).focusRestorer().focusGroup(),
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

/**
 * The logo, the page's title and, where [showsBackButton], its Back button; [backFocus] is the requester a page that
 * opens on Back attaches to it.
 */
@Composable
internal fun ScreenTitle(title: String, onBack: () -> Unit, backFocus: FocusRequester? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LogoMark(40.dp)
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        if (showsBackButton()) {
            SofaButton(
                onClick = onBack,
                modifier = if (backFocus == null) {
                    Modifier
                } else {
                    Modifier.focusRequester(backFocus)
                },
            ) { Text(stringResource(Res.string.settings_back)) }
        }
    }
}

/**
 * Whether a page draws its own Back button: everywhere but a TV driven by its remote, whose Back key is the way back
 * (the TV design guidelines: "Don't display a back button"); a TV shows it again once a mouse or finger is used.
 */
@Composable
internal fun showsBackButton(): Boolean =
    !LocalLayoutProfile.current.tv || LocalLastInput.current.kind == InputKind.POINTER

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
