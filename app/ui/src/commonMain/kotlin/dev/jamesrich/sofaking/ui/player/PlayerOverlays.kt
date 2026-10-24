package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.ScrubTiles
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.cant_play
import dev.jamesrich.sofaking.resources.cant_play_retry
import dev.jamesrich.sofaking.resources.cant_reach_stream
import dev.jamesrich.sofaking.resources.caption_timing_value
import dev.jamesrich.sofaking.resources.caption_timing_value_later
import dev.jamesrich.sofaking.resources.caption_timing_value_none
import dev.jamesrich.sofaking.resources.captions_indicator_timing
import dev.jamesrich.sofaking.resources.captions_indicator_timing_description
import dev.jamesrich.sofaking.resources.captions_indicator_timing_late
import dev.jamesrich.sofaking.resources.captions_indicator_timing_late_description
import dev.jamesrich.sofaking.resources.captions_off
import dev.jamesrich.sofaking.resources.captions_on
import dev.jamesrich.sofaking.resources.captions_timing_adjust
import dev.jamesrich.sofaking.resources.captions_timing_adjust_description
import dev.jamesrich.sofaking.resources.captions_timing_as_broadcast
import dev.jamesrich.sofaking.resources.captions_timing_earlier
import dev.jamesrich.sofaking.resources.captions_timing_later
import dev.jamesrich.sofaking.resources.captions_unavailable
import dev.jamesrich.sofaking.resources.key_legend
import dev.jamesrich.sofaking.resources.no_channel_number
import dev.jamesrich.sofaking.resources.no_working_source
import dev.jamesrich.sofaking.resources.no_working_source_hint
import dev.jamesrich.sofaking.resources.player_control_captions
import dev.jamesrich.sofaking.resources.player_control_exit_full_screen
import dev.jamesrich.sofaking.resources.player_control_full_screen
import dev.jamesrich.sofaking.resources.player_control_pause
import dev.jamesrich.sofaking.resources.player_control_play
import dev.jamesrich.sofaking.resources.player_control_retry
import dev.jamesrich.sofaking.resources.player_control_stream
import dev.jamesrich.sofaking.resources.player_paused
import dev.jamesrich.sofaking.resources.player_paused_resumes_live
import dev.jamesrich.sofaking.resources.player_paused_resumes_live_after
import dev.jamesrich.sofaking.resources.player_retry_in
import dev.jamesrich.sofaking.resources.player_retry_now
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.resources.stream_card_audio
import dev.jamesrich.sofaking.resources.stream_card_audio_codec
import dev.jamesrich.sofaking.resources.stream_card_buffered
import dev.jamesrich.sofaking.resources.stream_card_fps
import dev.jamesrich.sofaking.resources.stream_card_mbps
import dev.jamesrich.sofaking.resources.stream_card_seconds
import dev.jamesrich.sofaking.resources.stream_card_session
import dev.jamesrich.sofaking.resources.stream_card_size
import dev.jamesrich.sofaking.resources.stream_card_source
import dev.jamesrich.sofaking.resources.stream_card_title
import dev.jamesrich.sofaking.resources.stream_card_unknown
import dev.jamesrich.sofaking.resources.stream_card_video
import dev.jamesrich.sofaking.resources.stream_refused
import dev.jamesrich.sofaking.resources.stream_unsupported
import dev.jamesrich.sofaking.resources.up_next
import dev.jamesrich.sofaking.resources.vod_cant_play
import dev.jamesrich.sofaking.resources.vod_cant_play_retry
import dev.jamesrich.sofaking.resources.vod_seek_badge
import dev.jamesrich.sofaking.resources.vod_seek_seconds
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.LocalWindowFullScreen
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.ProgramBadges
import dev.jamesrich.sofaking.ui.RiseIn
import dev.jamesrich.sofaking.ui.SinkOut
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.episodeLine
import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.noListingText
import dev.jamesrich.sofaking.ui.oneDecimal
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaOverlayTrack
import dev.jamesrich.sofaking.ui.theme.tvFocus
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

internal val OverlayShape = RoundedCornerShape(8.dp)

internal fun CaptionToggleOutcome.labelRes(): StringResource = when (this) {
    CaptionToggleOutcome.ON -> Res.string.captions_on
    CaptionToggleOutcome.OFF -> Res.string.captions_off
    CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL -> Res.string.captions_unavailable
}

/** "2.0 s early", "1.5 s late" or "as broadcast", for the CC toast and the settings row. */
@Composable
internal fun captionTimingShort(ms: Int): String = when (CaptionPolicy.timingDirection(ms)) {
    CaptionTiming.EARLIER -> stringResource(Res.string.captions_timing_earlier, CaptionPolicy.advanceLabel(ms))
    CaptionTiming.LATER -> stringResource(Res.string.captions_timing_later, CaptionPolicy.advanceLabel(ms))
    CaptionTiming.AS_BROADCAST -> stringResource(Res.string.captions_timing_as_broadcast)
}

/** The settings row's value: "2.0 s earlier", "1.5 s later" or "As broadcast". */
@Composable
internal fun captionTimingText(ms: Int): String = when (CaptionPolicy.timingDirection(ms)) {
    CaptionTiming.EARLIER -> stringResource(Res.string.caption_timing_value, CaptionPolicy.advanceLabel(ms))
    CaptionTiming.LATER -> stringResource(Res.string.caption_timing_value_later, CaptionPolicy.advanceLabel(ms))
    CaptionTiming.AS_BROADCAST -> stringResource(Res.string.caption_timing_value_none)
}

/**
 * The CC toast: what the toggle did and, while Left and Right adjust this channel's timing, the timing they are
 * at. [timingMs] is null where the engine applies no lead.
 */
@Composable
internal fun CaptionToastPill(outcome: CaptionToggleOutcome, timingMs: Int?) {
    Column(
        Modifier
            .background(SofaOverlayScrim, OverlayShape)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(outcome.labelRes()),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (timingMs != null) {
            val timing = captionTimingShort(timingMs)
            val description = stringResource(Res.string.captions_timing_adjust_description, timing)
            Text(
                stringResource(Res.string.captions_timing_adjust, timing),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

/**
 * A short status over the video, announced to screen readers; stack several in a Column. [busy] leads with the loader.
 */
@Composable
internal fun StatusPill(text: String, style: TextStyle = MaterialTheme.typography.titleMedium, busy: Boolean = false) {
    Row(
        Modifier
            .background(SofaOverlayScrim, OverlayShape)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) SofaLoader(size = 22.dp)
        Text(text, style = style, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The digits being typed, or the miss notice for them, top right; keyed on presence so each digit updates it in place.
 */
@Composable
internal fun BoxScope.ChannelNumberBadge(numbers: ChannelNumberInput) {
    val miss = numbers.miss
    val noticeText = if (miss != null) stringResource(Res.string.no_channel_number, miss) else null
    val popIn = PopIn
    val popOut = PopOut
    AnimatedContent(
        targetState = numbers.entry.digits.ifEmpty { noticeText.orEmpty() },
        modifier = Modifier.align(Alignment.TopEnd),
        transitionSpec = { popIn togetherWith popOut },
        contentKey = { it.isNotEmpty() },
        label = "digits",
    ) { text ->
        if (text.isNotEmpty()) DigitsBadge(text)
    }
}

/** Typed channel digits, or the miss notice for them; the caller places it top right. */
@Composable
internal fun DigitsBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .screenPadding()
            .background(SofaOverlayScrim, OverlayShape)
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The server's answer when every source of a channel is down or quarantined. */
internal const val SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"

/**
 * Plain-English headline for a playback error; [code] is the ExoPlayer error name or a session code, and [live] tells
 * a channel from a library item or recording.
 */
internal fun failureReasonRes(code: String, live: Boolean = true): StringResource = when {
    SOURCE_UNAVAILABLE in code -> Res.string.no_working_source
    "NETWORK_CONNECTION" in code -> Res.string.cant_reach_stream
    "BAD_HTTP_STATUS" in code -> Res.string.stream_refused
    "PARSING" in code || "UNSUPPORTED" in code -> Res.string.stream_unsupported
    live -> Res.string.cant_play
    else -> Res.string.vod_cant_play
}

/** The [FailureCard] code for [e]: the server's error code, or `SESSION_UNAVAILABLE` when the server gave none. */
internal fun failureCode(e: Throwable): String = (e as? SofaKingException)?.code ?: SESSION_UNAVAILABLE

private const val SESSION_UNAVAILABLE = "SESSION_UNAVAILABLE"

/**
 * What the card says under the headline: a channel with no working source gets its own
 * line, and an item has no reconnect.
 */
internal fun failureHintRes(code: String, live: Boolean = true): StringResource = when {
    !live -> Res.string.vod_cant_play_retry
    SOURCE_UNAVAILABLE in code -> Res.string.no_working_source_hint
    else -> Res.string.cant_play_retry
}

/**
 * [retryInSeconds] counts down to the next automatic reconnect; 0 while it runs, null while none is scheduled. A
 * lambda, so the countdown recomposes only this card.
 */
@Composable
internal fun BoxScope.FailureCard(code: String, live: Boolean = true, retryInSeconds: () -> Int? = { null }) {
    val retryIn = retryInSeconds()
    Column(
        Modifier
            .align(Alignment.Center)
            .background(SofaOverlayScrim, OverlayShape)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(failureReasonRes(code, live)),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(failureHintRes(code, live)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (retryIn != null) {
            Text(
                if (retryIn > 0) {
                    stringResource(Res.string.player_retry_in, retryIn)
                } else {
                    stringResource(Res.string.player_retry_now)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The pause pill: how far behind live the picture is, and that resuming after [LIVE_RESUME_AFTER_MS] rejoins live. */
@Composable
internal fun PausePill(status: PauseStatus?) {
    val text = when {
        status == null -> stringResource(Res.string.player_paused)

        status.resumesLive -> stringResource(Res.string.player_paused_resumes_live, status.behindSeconds)

        else -> stringResource(
            Res.string.player_paused_resumes_live_after,
            status.behindSeconds,
            (LIVE_RESUME_AFTER_MS / 1_000L).toInt(),
        )
    }
    StatusPill(text)
}

/**
 * Thin primary-colored bar; [fraction] is 0..1. [color] is the filled part, for rows whose
 * background is already primary.
 */
@Composable
internal fun ProgressBar(fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(4.dp),
        color = color,
        trackColor = SofaOverlayTrack,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** The one-line key legend: shown on the first few info bars after install and on the stream card. */
@Composable
internal fun KeyLegend(modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.key_legend),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** The info bar; the caller places it at the bottom. [showKeyLegend] adds the key legend under the program. */
@Composable
internal fun InfoOverlay(
    lineup: Lineup,
    channel: Channel,
    now: Instant,
    captionsOn: Boolean,
    /** The channel's caption lead, or null where the engine applies none and the timing badge is left out. */
    captionAdvanceMs: Int?,
    modifier: Modifier = Modifier,
    scope: String? = null,
    showKeyLegend: Boolean = false,
) {
    val (current, next) = lineup.nowAndNext(channel, now)
    val timeFormat = rememberTimeFormatter()
    Column(
        modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .background(SofaOverlayScrim, OverlayShape)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        // The scope and captions badge wrap under a long name, and a name wider than the bar ends in an ellipsis.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel.logo != null) ChannelLogo(channel, 64.dp, 40.dp, Modifier.padding(end = 12.dp))
                Text(
                    channel.label(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (scope != null) {
                Text(
                    scope,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (captionsOn && captionAdvanceMs != null && captionAdvanceMs != 0) {
                val advance = CaptionPolicy.advanceLabel(captionAdvanceMs)
                val late = CaptionPolicy.timingDirection(captionAdvanceMs) == CaptionTiming.LATER
                val advanceDescription =
                    stringResource(
                        if (late) {
                            Res.string.captions_indicator_timing_late_description
                        } else {
                            Res.string.captions_indicator_timing_description
                        },
                        advance,
                    )
                Text(
                    stringResource(
                        if (late) Res.string.captions_indicator_timing_late else Res.string.captions_indicator_timing,
                        advance,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, OverlayShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .semantics { contentDescription = advanceDescription },
                )
            }
        }
        if (current == null) {
            Text(
                channel.noListingText(lineup.guideLoading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Row {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            current.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        ProgramBadges(
                            program = current,
                            live = false,
                            fill = MaterialTheme.colorScheme.primary,
                            onFill = MaterialTheme.colorScheme.onPrimary,
                            outline = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        listOfNotNull(
                            timeFormat.range(current.start, current.stop),
                            current.episodeLine(),
                        ).joinToString(DETAIL_SEPARATOR),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    current.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                current.icon?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(
                            start = 16.dp,
                        ).size(width = 160.dp, height = 90.dp).clip(OverlayShape),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(programProgress(current.start, current.stop, now))
        }
        next?.let {
            Text(
                stringResource(Res.string.up_next, it.title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showKeyLegend) {
            Spacer(Modifier.height(8.dp))
            KeyLegend()
        }
    }
}

/**
 * One player control, for a finger, a mouse or the D-pad: the icon carries the label as its description. The center
 * touch control is [primary] and larger; [size] is the tonal button's, 48 dp unless the row it sits in is tighter.
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
        FilledIconButton(onClick = onClick, modifier = modifier.size(64.dp).tvFocus(IconButtonDefaults.filledShape)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.size(size).tvFocus(IconButtonDefaults.filledShape),
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

/**
 * The info bar's slot along the bottom of the player, sliding up when [visible]. The alignment sits on
 * AnimatedVisibility itself: an align() inside its content would only place the bar within the animation's own wrapper.
 */
@Composable
internal fun BoxScope.InfoBar(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomStart), enter = RiseIn, exit = SinkOut) {
        Column(
            Modifier.fillMaxWidth().screenPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * The stream card, on Info held: the audio tracks to pick from, what the engine knows about the stream, the server's
 * source and session, and the key legend. [info] and [bufferedMs] are read again by the owner while it is open.
 */
@Composable
internal fun BoxScope.StreamCard(
    tracks: List<AudioTrack>,
    info: StreamInfo,
    stream: PlaybackStream?,
    bufferedMs: Long,
    onSelectTrack: (String) -> Unit,
) {
    val unknown = stringResource(Res.string.stream_card_unknown)
    // Focus starts on the playing track's row, so OK and Up/Down act on the tracks at once; with
    // none, the card itself holds it.
    val focusRequester = remember { FocusRequester() }
    val focusTrack = tracks.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester) }
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .screenPadding()
            .width(400.dp)
            .background(SofaOverlayScrim, OverlayShape)
            .padding(24.dp)
            .then(if (tracks.isEmpty()) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.stream_card_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (tracks.isNotEmpty()) {
            Text(
                stringResource(Res.string.stream_card_audio),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tracks.forEachIndexed { position, track ->
                TrackRow(
                    track.label,
                    track.selected,
                    onClick = { onSelectTrack(track.id) },
                    modifier = if (position == focusTrack) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
        }
        val video = listOfNotNull(
            info.videoCodec,
            if (info.width != null && info.height != null) {
                stringResource(Res.string.stream_card_size, info.width, info.height)
            } else {
                null
            },
            info.frameRate?.let { stringResource(Res.string.stream_card_fps, oneDecimal(it.toDouble())) },
            info.bitrateBps?.let { stringResource(Res.string.stream_card_mbps, oneDecimal(it / 1_000_000.0)) },
        )
        StatLine(stringResource(Res.string.stream_card_video), video.joinToString(DETAIL_SEPARATOR).ifEmpty { unknown })
        StatLine(stringResource(Res.string.stream_card_audio_codec), info.audioCodec ?: unknown)
        StatLine(
            stringResource(Res.string.stream_card_buffered),
            stringResource(
                Res.string.stream_card_seconds,
                oneDecimal(bufferedMs.milliseconds.toDouble(DurationUnit.SECONDS)),
            ),
        )
        StatLine(
            stringResource(Res.string.stream_card_source),
            stream?.providerId?.takeIf { it.isNotEmpty() } ?: unknown,
        )
        StatLine(stringResource(Res.string.stream_card_session), stream?.sessionId?.take(n = 8) ?: unknown)
        Spacer(Modifier.height(4.dp))
        KeyLegend()
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A track to pick; the playing one reads as [selected], and OK on any row plays it. */
@Composable
internal fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .tvFocus(OverlayShape, focusedScale = 1f)
            .background(if (focused) colors.primary else Color.Transparent, OverlayShape)
            .selectable(
                selected = selected,
                interactionSource = interactions,
                indication = ripple(),
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) SofaIcons.Recordings else SofaIcons.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (focused) colors.onPrimary else colors.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) colors.onPrimary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The center of the picture confirming a play or pause the bar did not come up for: a disc with the icon, shown for
 * a moment on each [pulse]. Icon only, so it never covers subtitles at the top or bottom of the frame.
 */
@Composable
internal fun BoxScope.TransportDisc(hint: TransportHint?, pulse: Int) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(pulse) {
        if (pulse == 0 || hint == null) return@LaunchedEffect
        shown = true
        delay(TRANSPORT_DISC_MS)
        shown = false
    }
    AnimatedVisibility(shown, Modifier.align(Alignment.Center), enter = PopIn, exit = PopOut) {
        Box(
            Modifier.size(72.dp).background(SofaOverlayScrim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (hint == TransportHint.PAUSE) SofaIcons.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (hint == TransportHint.PAUSE) {
                        Res.string.player_paused
                    } else {
                        Res.string.player_control_play
                    },
                ),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/**
 * A scrub the remote builds with the bar down: how far it moves, and the time it lands on, low in the middle of the
 * picture, so the bar does not cover it.
 */
@Composable
internal fun BoxScope.SeekBadge(targetMs: Long?, positionMs: () -> Long, scrub: ScrubTiles? = null) {
    AnimatedVisibility(
        targetMs != null,
        Modifier.align(Alignment.BottomCenter).screenPadding(),
        enter = PopIn,
        exit = PopOut,
    ) {
        val target = targetMs ?: return@AnimatedVisibility
        val delta = target - positionMs()
        val amount = if (abs(delta) < 60_000L) {
            stringResource(Res.string.vod_seek_seconds, abs(delta) / 1000)
        } else {
            formatPlayTime(abs(delta))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            scrub?.let { ScrubThumbnail(it, target) }
            StatusPill(
                stringResource(
                    Res.string.vod_seek_badge,
                    if (delta < 0) "−" else "+",
                    amount,
                    formatPlayTime(target),
                ),
            )
        }
    }
}

private const val TRANSPORT_DISC_MS = 650L
