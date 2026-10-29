package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.player_control_exit_full_screen
import dev.jamesrich.sofaking.resources.player_control_full_screen
import dev.jamesrich.sofaking.ui.LocalWindowFullScreen
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.tvFocus
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

/** Switches the window's full screen, as F does; nothing where the app cannot switch it (Android and iOS). */
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
