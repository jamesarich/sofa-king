package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.player_control_exit_full_screen
import dev.jamesrich.sofaking.resources.player_control_full_screen
import dev.jamesrich.sofaking.ui.LocalWindowFullScreen
import dev.jamesrich.sofaking.ui.arrowKeysMoveFocus
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.tvFocus
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * One player control, for a finger, a mouse or the D-pad: the icon carries the label as its description. The center
 * touch control is [primary], Material's medium icon button; the others are [CONTROL_SIZE] tonal buttons.
 */
@Composable
internal fun ControlIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    if (primary) {
        FilledIconButton(
            onClick = onClick,
            modifier = modifier.size(IconButtonDefaults.mediumContainerSize()).tvFocus(IconButtonDefaults.filledShape),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(IconButtonDefaults.mediumIconSize))
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.size(CONTROL_SIZE).tvFocus(IconButtonDefaults.filledShape),
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

/**
 * A control that opens a menu of the item's entries, for what is used less than the rest of the bar. [onOpen] is
 * called now and then while the menu is up, so the bar that holds it stays up too; the menu opens on its first entry.
 * [buttonModifier] goes on the button, which holds the focus.
 */
@Composable
internal fun MenuControl(
    item: PlayerButtonItem,
    entries: List<PlayerMenuEntry>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val opened by rememberUpdatedState(onOpen)
    LaunchedEffect(open) {
        while (open) {
            opened()
            delay(MENU_KEEP_BAR_MS)
        }
    }
    Box(modifier) {
        ControlIcon(item.icon, item.label, { open = true }, buttonModifier.semantics { role = Role.DropdownList })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.arrowKeysMoveFocus()) {
            val first = remember { FocusRequester() }
            entries.forEachIndexed { index, entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = { Icon(entry.icon, contentDescription = null) },
                    onClick = {
                        if (!entry.keepOpen) open = false
                        entry.onClick()
                        onOpen()
                    },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
            LaunchedEffect(Unit) { requestFocusWhenAttached(first) }
        }
    }
}

/** How often an open menu asks for the bar behind it to stay up; the bar leaves after [BAR_IDLE_MS]. */
private const val MENU_KEEP_BAR_MS = 2_000L

/** Retry once the stream has [failed], which OK does on the remote; play or pause, as [paused] says, until then. */
@Composable
internal fun PlayPauseControl(
    paused: Boolean,
    failed: Boolean,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val item = playPauseButtonItem(paused, failed, onPlayPause, onRetry)
    ControlIcon(item.icon, item.label, item.onClick, modifier = modifier, primary = primary)
}

/** Switches the window's full screen, as F does; nothing where the app cannot switch it (Android). */
@Composable
internal fun FullScreenControl() {
    val fullScreen = LocalWindowFullScreen.current ?: return
    ControlIcon(
        if (fullScreen.isFullScreen) SofaIcons.FullscreenExit else SofaIcons.Fullscreen,
        stringResource(
            if (fullScreen.isFullScreen) {
                Res.string.player_control_exit_full_screen
            } else {
                Res.string.player_control_full_screen
            },
        ),
        fullScreen::toggle,
    )
}

/**
 * The touch row of either player: [start], the [primary] button, [end], then the [buttons], the volume and full
 * screen, wrapping when the width runs out.
 */
@Composable
internal fun PointerControlRow(
    start: PlayerButtonItem,
    primary: PlayerButtonItem,
    end: PlayerButtonItem,
    onVolumeAdjust: () -> Unit,
    onVolumeFinish: () -> Unit,
    buttons: @Composable () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BAR_ROW_SPACING, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(BAR_ROW_SPACING),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ControlIcon(start.icon, start.label, start.onClick)
        ControlIcon(primary.icon, primary.label, primary.onClick, primary = true)
        ControlIcon(end.icon, end.label, end.onClick)
        buttons()
        VolumeControl(onAdjust = onVolumeAdjust, onDone = onVolumeFinish)
        FullScreenControl()
    }
}

internal val CONTROL_SIZE = 48.dp

/** The gap between the controls of a player bar's row, and between the rows and lines of its panel. */
internal val BAR_ROW_SPACING = 8.dp

/** The gap between a row of controls and the name of the one focused. */
internal val BAR_LABEL_GAP = 4.dp

/** The padding inside a player bar's panel. */
internal val BAR_CARD_PADDING = 24.dp
