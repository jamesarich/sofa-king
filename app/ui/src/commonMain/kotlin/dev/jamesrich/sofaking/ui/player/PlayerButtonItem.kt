package dev.jamesrich.sofaking.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.captions_off
import dev.jamesrich.sofaking.resources.captions_on
import dev.jamesrich.sofaking.resources.player_control_captions
import dev.jamesrich.sofaking.resources.player_control_pause
import dev.jamesrich.sofaking.resources.player_control_play
import dev.jamesrich.sofaking.resources.player_control_retry
import dev.jamesrich.sofaking.resources.player_control_sleep
import dev.jamesrich.sofaking.resources.player_control_stream
import dev.jamesrich.sofaking.resources.player_sleep_end_item
import dev.jamesrich.sofaking.resources.player_sleep_end_program
import dev.jamesrich.sofaking.resources.player_sleep_minutes
import dev.jamesrich.sofaking.resources.player_zoom_fill
import dev.jamesrich.sofaking.resources.player_zoom_fit
import dev.jamesrich.sofaking.resources.player_zoom_notice
import dev.jamesrich.sofaking.resources.player_zoom_stretch
import dev.jamesrich.sofaking.resources.vod_control_speed
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** One button of the row: what it shows, what a screen reader says it is and its state, and what it does. */
internal class PlayerButtonItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val state: String? = null,
)

/** What a [VideoZoom] is called on its button and in the note a press shows. */
internal val VideoZoom.label: StringResource
    get() = when (this) {
        VideoZoom.FIT -> Res.string.player_zoom_fit
        VideoZoom.FILL -> Res.string.player_zoom_fill
        VideoZoom.STRETCH -> Res.string.player_zoom_stretch
    }

/** Zoom on either bar, named with the zoom it is at, as "Zoom: Fit". */
@Composable
internal fun zoomButtonItem(zoom: VideoZoom, onClick: () -> Unit): PlayerButtonItem = PlayerButtonItem(
    SofaIcons.AspectRatio,
    stringResource(Res.string.player_zoom_notice, stringResource(zoom.label)),
    onClick,
)

/** Retry once the stream has [failed]; play or pause, as [paused] says, until then. */
@Composable
internal fun playPauseButtonItem(
    paused: Boolean,
    failed: Boolean,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
): PlayerButtonItem = if (failed) {
    PlayerButtonItem(Icons.Filled.Refresh, stringResource(Res.string.player_control_retry), onRetry)
} else {
    PlayerButtonItem(
        if (paused) Icons.Filled.PlayArrow else SofaIcons.Pause,
        stringResource(if (paused) Res.string.player_control_play else Res.string.player_control_pause),
        onPlayPause,
    )
}

/** Captions on either bar, with their on or off state for a screen reader. */
@Composable
internal fun captionsButtonItem(captionsOn: Boolean, onClick: () -> Unit): PlayerButtonItem = PlayerButtonItem(
    if (captionsOn) SofaIcons.ClosedCaption else SofaIcons.ClosedCaptionOff,
    stringResource(Res.string.player_control_captions),
    onClick,
    state = stringResource(if (captionsOn) Res.string.captions_on else Res.string.captions_off),
)

/** The stream card's button, with the audio-track icon where there is a track to pick. */
@Composable
internal fun streamInfoButtonItem(hasAudioChoice: Boolean, onClick: () -> Unit): PlayerButtonItem = PlayerButtonItem(
    if (hasAudioChoice) SofaIcons.Audiotrack else Icons.Filled.Info,
    stringResource(Res.string.player_control_stream),
    onClick,
)

/** The library player's speeds,which its Speed button steps through and back to the first. */
internal val PLAYBACK_SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)

/** The speed after [speed] on the Speed button; one not in [PLAYBACK_SPEEDS] goes back to normal. */
internal fun nextSpeed(speed: Float): Float =
    PLAYBACK_SPEEDS.getOrNull(PLAYBACK_SPEEDS.indexOf(speed) + 1) ?: PLAYBACK_SPEEDS.first()

/** [speed] as the Speed button names it, as "1.25" or "2". */
internal fun speedText(speed: Float): String = speed.toString().removeSuffix(".0")

/** The Speed button, named with the speed playing now, as "Speed 1.5×". */
@Composable
internal fun speedButtonItem(speed: Float, onClick: () -> Unit): PlayerButtonItem =
    PlayerButtonItem(SofaIcons.Speed, stringResource(Res.string.vod_control_speed, speedText(speed)), onClick)

/**
 * The sleep timer on either bar, named with what it is set to, as "Sleep in 24 min"; a press steps it on as
 * [SleepTimer.step] says. The minutes are read from the clock as the bar composes, which a press or the bar coming
 * up again does, so they are right as they are pressed rather than up to a minute behind.
 */
@Composable
internal fun sleepButtonItem(sleep: SleepTimer, onClick: () -> Unit): PlayerButtonItem {
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val label = when {
        sleep.choice == SleepChoice.OFF -> stringResource(Res.string.player_control_sleep)
        sleep.atEndOfItem -> stringResource(Res.string.player_sleep_end_item)
        sleep.choice == SleepChoice.END -> stringResource(Res.string.player_sleep_end_program)
        else -> stringResource(Res.string.player_sleep_minutes, sleep.minutesLeft(nowMs) ?: 0)
    }
    return PlayerButtonItem(SofaIcons.Bedtime, label, onClick)
}
