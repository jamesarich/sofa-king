package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.captions_off
import dev.jamesrich.sofaking.resources.captions_on
import dev.jamesrich.sofaking.resources.player_control_captions
import dev.jamesrich.sofaking.resources.player_control_exit_full_screen
import dev.jamesrich.sofaking.resources.player_control_full_screen
import dev.jamesrich.sofaking.resources.player_control_pause
import dev.jamesrich.sofaking.resources.player_control_play
import dev.jamesrich.sofaking.resources.player_control_retry
import dev.jamesrich.sofaking.resources.player_control_stream
import dev.jamesrich.sofaking.ui.LocalWindowFullScreen
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource

/**
 * One player control, for a finger, a mouse or the D-pad: the icon carries the label as its description. The center
 * touch control is [primary], Material's medium icon button; [size] is the tonal button's, 48 dp unless the row it
 * sits in is tighter.
 */
@Composable
internal fun ControlIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    size: Dp = CONTROL_SIZE,
) {
    if (primary) {
        FilledIconButton(
            onClick = onClick,
            modifier = modifier.size(IconButtonDefaults.mediumContainerSize()).tvFocus(),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(IconButtonDefaults.mediumIconSize))
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.size(size).tvFocus(),
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
    primary: Boolean = false,
    size: Dp = CONTROL_SIZE,
) {
    if (failed) {
        ControlIcon(
            Icons.Filled.Refresh,
            stringResource(Res.string.player_control_retry),
            onRetry,
            primary = primary,
            size = size,
        )
    } else {
        ControlIcon(
            if (paused) Icons.Filled.PlayArrow else SofaIcons.Pause,
            stringResource(if (paused) Res.string.player_control_play else Res.string.player_control_pause),
            onPlayPause,
            primary = primary,
            size = size,
        )
    }
}

/** The captions toggle; its icon and state description say whether they are on. */
@Composable
internal fun CaptionsControl(captionsOn: Boolean, onClick: () -> Unit, size: Dp = CONTROL_SIZE) {
    val captionsState = stringResource(if (captionsOn) Res.string.captions_on else Res.string.captions_off)
    ControlIcon(
        if (captionsOn) SofaIcons.ClosedCaption else SofaIcons.ClosedCaptionOff,
        stringResource(Res.string.player_control_captions),
        onClick,
        size = size,
        modifier = Modifier.semantics { stateDescription = captionsState },
    )
}

/** Opens the stream card, showing the audio icon when [hasAudioChoice], since the card holds the audio picker. */
@Composable
internal fun StreamInfoControl(hasAudioChoice: Boolean, onClick: () -> Unit, size: Dp = CONTROL_SIZE) {
    ControlIcon(
        if (hasAudioChoice) SofaIcons.Audiotrack else Icons.Filled.Info,
        stringResource(Res.string.player_control_stream),
        onClick,
        size = size,
    )
}

/** Switches the window's full screen, as F does; nothing where the app cannot switch it (Android and iOS). */
@Composable
internal fun FullScreenControl(size: Dp = CONTROL_SIZE) {
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
        size = size,
    )
}

internal val CONTROL_SIZE = 48.dp
