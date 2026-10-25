package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.captions_off
import dev.jamesrich.sofaking.resources.captions_on
import dev.jamesrich.sofaking.resources.player_control_captions
import dev.jamesrich.sofaking.resources.retry
import dev.jamesrich.sofaking.resources.vod_control_back
import dev.jamesrich.sofaking.resources.vod_control_back_to_live
import dev.jamesrich.sofaking.resources.vod_control_forward
import dev.jamesrich.sofaking.resources.vod_control_next
import dev.jamesrich.sofaking.resources.vod_control_skip_break
import dev.jamesrich.sofaking.resources.vod_control_skip_credits
import dev.jamesrich.sofaking.resources.vod_control_skip_credits_countdown
import dev.jamesrich.sofaking.resources.vod_control_skip_intro
import dev.jamesrich.sofaking.resources.vod_control_skip_intro_countdown
import dev.jamesrich.sofaking.resources.vod_control_start_over
import dev.jamesrich.sofaking.resources.vod_ends_at
import dev.jamesrich.sofaking.resources.vod_mode_audio
import dev.jamesrich.sofaking.resources.vod_mode_direct
import dev.jamesrich.sofaking.resources.vod_mode_remux
import dev.jamesrich.sofaking.resources.vod_mode_transcode
import dev.jamesrich.sofaking.resources.vod_options_hint
import dev.jamesrich.sofaking.resources.vod_position_description
import dev.jamesrich.sofaking.resources.vod_tracks_title
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** Where the item is, when it ends and how long it is, under the seek bar. */
@Composable
private fun VodTimes(positionMs: () -> Long, scrubMs: Long?, durationMs: Long?, speed: Float) {
    val positionText = formatPlayTime(scrubMs ?: positionMs())
    val durationText = durationMs?.let(::formatPlayTime)
    val now by rememberMinuteNow()
    val timeFormat = rememberTimeFormatter()
    val endsText = vodEndsAt(now, scrubMs ?: positionMs(), durationMs, speed)?.let {
        stringResource(Res.string.vod_ends_at, timeFormat.format(it))
    }
    val description = if (durationText != null) {
        stringResource(Res.string.vod_position_description, positionText, durationText)
    } else {
        positionText
    }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }) {
        Text(
            positionText,
            style = MaterialTheme.typography.titleMedium,
            color = if (scrubMs != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.weight(1f))
        endsText?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        durationText?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The row's button for [button], with a recording's captions shown as [captionsOn], the picture at [zoom], the
 * [sleep] timer and the [speed].
 */
@Composable
internal fun vodButtonItem(
    button: VodButton,
    captionsOn: Boolean,
    zoom: VideoZoom,
    autoSkipSeconds: Int?,
    creditsToNext: Boolean,
    sleep: SleepTimer,
    speed: Float,
    onClick: () -> Unit,
): PlayerButtonItem = when (button) {
    VodButton.SKIP_BREAK -> PlayerButtonItem(
        SofaIcons.FastForward,
        stringResource(Res.string.vod_control_skip_break),
        onClick,
    )

    VodButton.SKIP_INTRO -> PlayerButtonItem(
        SofaIcons.FastForward,
        autoSkipSeconds?.let { stringResource(Res.string.vod_control_skip_intro_countdown, it) }
            ?: stringResource(Res.string.vod_control_skip_intro),
        onClick,
    )

    VodButton.SKIP_CREDITS ->
        if (creditsToNext) {
            PlayerButtonItem(SofaIcons.SkipNext, stringResource(Res.string.vod_control_next), onClick)
        } else {
            PlayerButtonItem(
                SofaIcons.FastForward,
                autoSkipSeconds?.let { stringResource(Res.string.vod_control_skip_credits_countdown, it) }
                    ?: stringResource(Res.string.vod_control_skip_credits),
                onClick,
            )
        }

    VodButton.TRACKS -> PlayerButtonItem(SofaIcons.Subtitles, stringResource(Res.string.vod_tracks_title), onClick)

    VodButton.CAPTIONS -> PlayerButtonItem(
        if (captionsOn) SofaIcons.ClosedCaption else SofaIcons.ClosedCaptionOff,
        stringResource(Res.string.player_control_captions),
        onClick,
        state = stringResource(if (captionsOn) Res.string.captions_on else Res.string.captions_off),
    )

    VodButton.ZOOM -> zoomButtonItem(zoom, onClick)

    VodButton.SLEEP -> sleepButtonItem(sleep, onClick)

    VodButton.SPEED -> speedButtonItem(speed, onClick)

    VodButton.START_OVER -> PlayerButtonItem(
        SofaIcons.Replay,
        stringResource(Res.string.vod_control_start_over),
        onClick,
    )

    VodButton.NEXT -> PlayerButtonItem(SofaIcons.SkipNext, stringResource(Res.string.vod_control_next), onClick)

    VodButton.BACK_TO_LIVE -> PlayerButtonItem(
        SofaIcons.LiveTv,
        stringResource(Res.string.vod_control_back_to_live),
        onClick,
    )
}

@Composable
private fun modeLabel(mode: String): String? = when (mode) {
    Wire.VodMode.DIRECT -> stringResource(Res.string.vod_mode_direct)
    Wire.VodMode.REMUX -> stringResource(Res.string.vod_mode_remux)
    Wire.VodMode.AUDIO -> stringResource(Res.string.vod_mode_audio)
    Wire.VodMode.TRANSCODE -> stringResource(Res.string.vod_mode_transcode)
    else -> null
}

/** The buttons that take focus as playback reaches what they skip. */
internal val SKIP_BUTTONS = setOf(VodButton.SKIP_BREAK, VodButton.SKIP_INTRO, VodButton.SKIP_CREDITS)

/** The buttons that keep focus after a press, as each press steps them on (the track card takes it itself). */
private val STAYING_BUTTONS =
    setOf(VodButton.CAPTIONS, VodButton.ZOOM, VodButton.SLEEP, VodButton.SPEED, VodButton.TRACKS)

private val BarShape = RoundedCornerShape(8.dp)

/**
 * The info bar's panel: the title and how the item plays, the seek bar and the times, and the buttons. [buttons] are
 * the bar's buttons, which a press runs through [press]; a finger or a mouse also gets the transport and the volume.
 */
@Composable
internal fun BoxScope.VodInfoBar(
    parts: VodParts,
    title: VodTitle,
    mode: String?,
    visible: Boolean,
    buttons: List<PlayerButtonItem>,
    controls: VodControls,
) {
    val state = parts.state
    val density = LocalDensity.current
    InfoBar(visible) {
        Column(
            Modifier
                .onSizeChanged { parts.barHeight = with(density) { it.height.toDp() } }
                .widthIn(max = 1200.dp)
                .fillMaxWidth()
                .background(SofaOverlayScrim, BarShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VodBarHeading(title, mode)
            VodSeekArea(parts, title)
            VodTimes(
                positionMs = { state.positionMs },
                scrubMs = state.scrubTarget,
                durationMs = state.durationMs,
                speed = parts.vod.speed,
            )
            VodBarButtons(parts, buttons, controls)
        }
    }
}

/** The title and episode, announced as the bar comes up, beside how the server plays the item ([mode]). */
@Composable
private fun VodBarHeading(title: VodTitle, mode: String?) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // The ticking times below are not announced, or a screen reader would read every second of them.
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Text(
                title.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            title.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        mode?.let { modeLabel(it) }?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The seek bar: a slider for a finger or a mouse, a bar for the remote; a scrub shows its thumbnail over it. */
@Composable
private fun VodSeekArea(parts: VodParts, title: VodTitle) {
    val state = parts.state
    val length = state.durationMs?.takeIf { it > 0 }
    Box(Modifier.fillMaxWidth()) {
        if (LocalLastInput.current.kind == InputKind.POINTER && length != null) {
            VodSeekSlider(
                positionMs = { state.positionMs },
                lengthMs = length,
                markers = title.markers,
                onScrub = state::dragTo,
                onSeek = { target ->
                    state.endScrub()
                    parts.vod.seek(target)
                    state.showInfo()
                    parts.focus.picture.requestFocus()
                },
            )
        } else {
            VodSeekBar(
                positionMs = { state.positionMs },
                durationMs = state.durationMs,
                scrubMs = state.scrubTarget,
                markers = title.markers,
            )
        }
        val target = state.scrubTarget
        if (title.scrub != null && target != null && length != null) {
            ScrubThumbnailOverBar(title.scrub, target, length)
        }
    }
}

/**
 * The bar's buttons: for a finger or a mouse in one row with back, play or pause, forward, the volume and full
 * screen; for the remote as a row the D-pad enters, with a hint until it does.
 */
@Composable
private fun VodBarButtons(parts: VodParts, buttons: List<PlayerButtonItem>, controls: VodControls) {
    val state = parts.state
    val vod = parts.vod
    if (LocalLastInput.current.kind == InputKind.POINTER) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            ControlIcon(SofaIcons.Replay10, stringResource(Res.string.vod_control_back), {
                vod.seek(state.positionMs - VodScrub.BACK_STEP_MS)
                state.showInfo()
            })
            val paused = !vod.player.status.collectAsStateWithLifecycle().value.playWhenReady
            PlayPauseControl(paused, failed = state.failure != null, onPlayPause = {
                controls.playPause()
                state.showInfo()
            }, onRetry = controls::retry, primary = true)
            ControlIcon(SofaIcons.Forward30, stringResource(Res.string.vod_control_forward), {
                vod.seek(state.positionMs + VodScrub.FORWARD_STEP_MS)
                state.showInfo()
            })
            PlayerButtonRow(buttons, entry = parts.focus.buttons, onFocus = {
                state.buttonsFocused = it
            }, showLabel = false)
            VolumeControl(onAdjust = state::showInfo, onDone = {
                state.showInfo()
                parts.focus.picture.requestFocus()
            })
            FullScreenControl()
        }
    } else if (buttons.isNotEmpty()) {
        PlayerButtonRow(buttons, entry = parts.focus.buttons, onFocus = { state.buttonsFocused = it }, showLabel = true)
        if (!state.buttonsFocused) {
            Text(
                stringResource(Res.string.vod_options_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A press on the bar's [button]: it skips or opens what it names, or steps its setting on ([sleep], the speed). The
 * bar stays up; a seek moves on and the picture takes the keys again, while Captions, Zoom, Sleep and Speed stay for
 * another press.
 */
internal fun VodParts.press(
    button: VodButton,
    spots: VodSpots,
    creditsToNext: Boolean,
    controls: VodControls,
    sleep: SleepTimer,
) {
    when (button) {
        VodButton.SKIP_BREAK -> spots.breakEnd?.let(vod::seek)
        VodButton.SKIP_INTRO -> spots.intro?.endMs?.let(vod::seek)
        VodButton.SKIP_CREDITS -> if (creditsToNext) controls.playNext() else spots.credits?.endMs?.let(vod::seek)
        VodButton.TRACKS -> if (state.tracksOpen) state.closeTracks() else state.openTracks()
        VodButton.CAPTIONS -> controls.toggleCaptions()
        VodButton.ZOOM -> controls.zoom()
        VodButton.SLEEP -> sleep.step(Clock.System.now().toEpochMilliseconds(), endOfItem = true)
        VodButton.SPEED -> vod.speed = nextSpeed(vod.speed)
        VodButton.START_OVER -> vod.seek(0)
        VodButton.NEXT -> controls.playNext()
        VodButton.BACK_TO_LIVE -> controls.backToLive()
    }
    state.showInfo()
    if (button !in STAYING_BUTTONS) focus.picture.requestFocus()
}
