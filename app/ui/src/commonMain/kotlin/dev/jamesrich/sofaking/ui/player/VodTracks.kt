package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.SubtitleLook
import dev.jamesrich.sofaking.data.SubtitleRaise
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.stream_card_audio
import dev.jamesrich.sofaking.resources.vod_tracks_earlier
import dev.jamesrich.sofaking.resources.vod_tracks_later
import dev.jamesrich.sofaking.resources.vod_tracks_subtitles
import dev.jamesrich.sofaking.resources.vod_tracks_subtitles_off
import dev.jamesrich.sofaking.resources.vod_tracks_timing
import dev.jamesrich.sofaking.resources.vod_tracks_title
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * One audio or subtitle track the item offers: its index, and what the picker calls it. A [picture] subtitle has
 * no text, so the server draws it into the picture.
 */
data class VodTrack(val index: Int, val label: String, val picture: Boolean = false)

/**
 * The tracks the Audio and subtitles card lists. [select] plays the item again from a position with an audio
 * track and a picture subtitle drawn in (each null for none picked), and [loadSubtitles] reads a text subtitle
 * track's cues, null when the server cannot give them.
 */
class VodTracks(
    val audio: List<VodTrack> = emptyList(),
    val subtitles: List<VodTrack> = emptyList(),
    val initialAudio: Int? = null,
    val initialSubtitle: Int? = null,
    val select: suspend (audio: Int?, pictureSubtitle: Int?, positionMs: Long) -> Unit = { _, _, _ -> },
    val loadSubtitles: suspend (index: Int) -> Subtitles? = { null },
    /** Told the audio and subtitle tracks after each pick, null for the default audio or no subtitles. */
    val onPicked: (audio: Int?, subtitle: Int?) -> Unit = { _, _ -> },
) {
    /** True when there is a choice to make: more than one audio track, or any subtitles. */
    val pickable: Boolean get() = audio.size > 1 || subtitles.isNotEmpty()

    /** True when subtitle track [index] is pictures the server draws, rather than text the player does. */
    fun isPicture(index: Int?): Boolean = index != null && subtitles.any { it.index == index && it.picture }

    /**
     * The subtitle track the CC key turns on: the one shown [last] while the item plays, else the one it started
     * with, else the first text track, else the first track; null when there are none.
     */
    fun subtitleToTurnOn(last: Int?): Int? =
        listOfNotNull(last, initialSubtitle).firstOrNull { index -> subtitles.any { it.index == index } }
            ?: subtitles.firstOrNull { !it.picture }?.index
            ?: subtitles.firstOrNull()?.index
}

/** The row the Audio and subtitles card opens on. */
internal sealed interface TracksFocus {
    data class Audio(val index: Int) : TracksFocus

    /** A subtitle track by its index, or Off for null. */
    data class Subtitle(val index: Int?) : TracksFocus
}

/**
 * The playing audio track when there is a choice of audio, otherwise the subtitle track on screen (Off when none is),
 * so the card opens on what is playing.
 */
internal fun tracksFocus(tracks: VodTracks, audio: Int?, subtitle: Int?): TracksFocus = when {
    tracks.audio.size > 1 -> TracksFocus.Audio(
        audio?.takeIf { a ->
            tracks.audio.any { it.index == a }
        } ?: tracks.audio.first().index,
    )

    else -> TracksFocus.Subtitle(subtitle?.takeIf { s -> tracks.subtitles.any { it.index == s } })
}

/** How far one press of Earlier or Later moves the subtitles, and how far from the file's own timing they may go. */
internal const val SUBTITLE_OFFSET_STEP_MS = 250L
internal const val SUBTITLE_OFFSET_MAX_MS = 10_000L

/** [offsetMs] moved [steps] presses later (earlier when negative), within [SUBTITLE_OFFSET_MAX_MS] either way. */
internal fun stepSubtitleOffset(offsetMs: Long, steps: Int): Long =
    (offsetMs + steps * SUBTITLE_OFFSET_STEP_MS).coerceIn(-SUBTITLE_OFFSET_MAX_MS, SUBTITLE_OFFSET_MAX_MS)

/** An offset as the card shows it: "+0.25 s" for later, "−1.5 s" for earlier, "0 s" for the file's own timing. */
internal fun subtitleOffsetText(offsetMs: Long): String {
    if (offsetMs == 0L) return "0 s"
    val ms = abs(offsetMs)
    val fraction = (ms % 1000).toString().padStart(3, '0').trimEnd('0')
    val seconds = if (fraction.isEmpty()) "${ms / 1000}" else "${ms / 1000}.$fraction"
    return "${if (offsetMs > 0) "+" else "\u2212"}$seconds s"
}

/**
 * The Audio and subtitles card at the side of the player: the audio tracks, then Off and the subtitle tracks, and
 * with a text subtitle on ([offsetMs] not null), Earlier and Later to move it against the picture. Focus starts on
 * what is playing ([tracksFocus]); OK picks a row and Back closes the card.
 */
@Composable
internal fun BoxScope.TracksCard(
    tracks: VodTracks,
    audio: Int?,
    subtitle: Int?,
    onAudio: (Int) -> Unit,
    onSubtitle: (Int?) -> Unit,
    offsetMs: Long? = null,
    onOffset: (Long) -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val start = remember { tracksFocus(tracks, audio, subtitle) }
    fun focusFor(row: TracksFocus) = if (row == start) Modifier.focusRequester(focus) else Modifier
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus) }
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .screenPadding()
            .width(420.dp)
            .background(SofaOverlayScrim, MaterialTheme.shapes.small)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.vod_tracks_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (tracks.audio.size > 1) {
            Text(
                stringResource(Res.string.stream_card_audio),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tracks.audio.forEach { track ->
                TrackRow(track.label, track.index == audio, onClick = {
                    onAudio(track.index)
                }, modifier = focusFor(TracksFocus.Audio(track.index)))
            }
        }
        if (tracks.subtitles.isNotEmpty()) {
            Text(
                stringResource(Res.string.vod_tracks_subtitles),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TrackRow(
                stringResource(Res.string.vod_tracks_subtitles_off),
                subtitle == null,
                onClick = { onSubtitle(null) },
                modifier = focusFor(TracksFocus.Subtitle(null)),
            )
            tracks.subtitles.forEach { track ->
                TrackRow(track.label, track.index == subtitle, onClick = {
                    onSubtitle(track.index)
                }, modifier = focusFor(TracksFocus.Subtitle(track.index)))
            }
        }
        if (offsetMs != null) {
            SubtitleTiming(offsetMs, onOffset)
        }
    }
}

/** The subtitle timing: Earlier and Later step [offsetMs] through [onOffset], with the offset between them. */
@Composable
private fun SubtitleTiming(offsetMs: Long, onOffset: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.vod_tracks_timing),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SofaButton(onClick = {
                onOffset(stepSubtitleOffset(offsetMs, -1))
            }) { Text(stringResource(Res.string.vod_tracks_earlier)) }
            Text(
                subtitleOffsetText(offsetMs),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            SofaButton(onClick = {
                onOffset(stepSubtitleOffset(offsetMs, 1))
            }) { Text(stringResource(Res.string.vod_tracks_later)) }
        }
    }
}

/**
 * The subtitle on screen, centered [bottom] above the bottom edge's overscan, at [look]'s size; on its box, or
 * without one in white with a shadow, so it reads over a light picture too.
 */
@Composable
internal fun BoxScope.SubtitleText(text: String, bottom: Dp, look: SubtitleLook = SubtitleLook()) {
    val base = MaterialTheme.typography.headlineSmall
    val style = base.copy(
        fontSize = base.fontSize * look.size.scale,
        lineHeight = base.lineHeight * look.size.scale,
        shadow = if (look.box) null else SUBTITLE_SHADOW,
    )
    val surface = if (look.box) Modifier.background(SofaOverlayScrim, MaterialTheme.shapes.small) else Modifier
    Box(Modifier.align(Alignment.BottomCenter).screenPadding().padding(bottom = bottom)) {
        Text(
            text,
            style = style,
            color = if (look.box) MaterialTheme.colorScheme.onSurface else Color.White,
            textAlign = TextAlign.Center,
            modifier = surface.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** How far above the bottom [SubtitleRaise] lifts a library item's subtitles, past the usual gap. */
internal val SubtitleRaise.height: Dp
    get() = when (this) {
        SubtitleRaise.NONE -> 0.dp
        SubtitleRaise.LOW -> 72.dp
        SubtitleRaise.HIGH -> 156.dp
    }

private val SUBTITLE_SHADOW = Shadow(Color.Black, Offset(2f, 2f), blurRadius = 6f)
