package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.StreamHealth
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.stream_card_audio
import dev.jamesrich.sofaking.resources.stream_card_audio_codec
import dev.jamesrich.sofaking.resources.stream_card_audio_output
import dev.jamesrich.sofaking.resources.stream_card_buffered
import dev.jamesrich.sofaking.resources.stream_card_decoded_channels
import dev.jamesrich.sofaking.resources.stream_card_decoded_layout
import dev.jamesrich.sofaking.resources.stream_card_decoded_mono
import dev.jamesrich.sofaking.resources.stream_card_decoded_stereo
import dev.jamesrich.sofaking.resources.stream_card_fps
import dev.jamesrich.sofaking.resources.stream_card_health
import dev.jamesrich.sofaking.resources.stream_card_mbps
import dev.jamesrich.sofaking.resources.stream_card_passed_through
import dev.jamesrich.sofaking.resources.stream_card_passed_through_as
import dev.jamesrich.sofaking.resources.stream_card_seconds
import dev.jamesrich.sofaking.resources.stream_card_session
import dev.jamesrich.sofaking.resources.stream_card_size
import dev.jamesrich.sofaking.resources.stream_card_source
import dev.jamesrich.sofaking.resources.stream_card_stalls
import dev.jamesrich.sofaking.resources.stream_card_switches
import dev.jamesrich.sofaking.resources.stream_card_title
import dev.jamesrich.sofaking.resources.stream_card_unknown
import dev.jamesrich.sofaking.resources.stream_card_video
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.listRowColors
import dev.jamesrich.sofaking.ui.listRowShapes
import dev.jamesrich.sofaking.ui.oneDecimal
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

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
    health: StreamHealth? = null,
) {
    // Focus starts on the playing track's row, so OK and Up/Down act on the tracks at once; with
    // none, the card itself holds it.
    val focusRequester = remember { FocusRequester() }
    val focusTrack = tracks.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester) }
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .screenPadding()
            .width(420.dp)
            .background(SofaOverlayScrim, MaterialTheme.shapes.small)
            .padding(24.dp)
            .then(if (tracks.isEmpty()) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .verticalScroll(rememberScrollState()),
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
        StreamStats(info, stream, bufferedMs, health)
        Spacer(Modifier.height(4.dp))
        KeyLegend()
    }
}

/**
 * What the engine knows about the video and audio, how much is buffered, the server's source and session, and what the
 * server's relay has seen of the session's media when it says.
 */
@Composable
private fun StreamStats(info: StreamInfo, stream: PlaybackStream?, bufferedMs: Long, health: StreamHealth?) {
    val unknown = stringResource(Res.string.stream_card_unknown)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        info.audioOutput?.let { StatLine(stringResource(Res.string.stream_card_audio_output), audioOutputText(it)) }
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
        if (health != null) {
            StatLine(
                stringResource(Res.string.stream_card_health),
                listOf(
                    stringResource(Res.string.stream_card_mbps, oneDecimal(health.bitrateKbps / KBPS_PER_MBPS)),
                    pluralStringResource(Res.plurals.stream_card_stalls, health.stalls.toInt(), health.stalls.toInt()),
                    pluralStringResource(
                        Res.plurals.stream_card_switches,
                        health.sourceSwitches,
                        health.sourceSwitches,
                    ),
                ).joinToString(DETAIL_SEPARATOR),
            )
        }
    }
}

/** A receiver decodes what passes through; what the app decodes is named by its channel layout. */
@Composable
private fun audioOutputText(output: AudioOutput): String = when (output) {
    is AudioOutput.PassedThrough -> output.codec?.let { stringResource(Res.string.stream_card_passed_through_as, it) }
        ?: stringResource(Res.string.stream_card_passed_through)

    is AudioOutput.Decoded -> when (output.channels) {
        1 -> stringResource(Res.string.stream_card_decoded_mono)
        2 -> stringResource(Res.string.stream_card_decoded_stereo)
        SURROUND_5_1 -> stringResource(Res.string.stream_card_decoded_layout, "5.1")
        SURROUND_7_1 -> stringResource(Res.string.stream_card_decoded_layout, "7.1")
        else -> pluralStringResource(Res.plurals.stream_card_decoded_channels, output.channels, output.channels)
    }
}

private const val SURROUND_5_1 = 6
private const val SURROUND_7_1 = 8

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

/**
 * A track to pick, Material's single-selection list item with its radio button: the playing one reads as [selected],
 * and OK on any row plays it. Over the picture, a row has no container until it takes focus.
 */
@Composable
internal fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shapes = listRowShapes()
    val interactions = remember { MutableInteractionSource() }
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tvFocus(shapes.shape, focusedScale = 1f),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        shapes = shapes,
        colors = listRowColors(interactions, tintSelected = false, rest = Color.Transparent),
        interactionSource = interactions,
    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

private const val KBPS_PER_MBPS = 1_000.0
