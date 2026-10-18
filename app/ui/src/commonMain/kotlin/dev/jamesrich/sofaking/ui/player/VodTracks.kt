package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import org.jetbrains.compose.resources.stringResource

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
    tracks.audio.size > 1 -> TracksFocus.Audio(audio?.takeIf { a -> tracks.audio.any { it.index == a } } ?: tracks.audio.first().index)
    else -> TracksFocus.Subtitle(subtitle?.takeIf { s -> tracks.subtitles.any { it.index == s } })
}

/**
 * The Audio and subtitles card at the side of the player: the audio tracks, then Off and the subtitle tracks. Focus
 * starts on what is playing ([tracksFocus]); OK picks a row and Back closes the card.
 */
@Composable
internal fun BoxScope.TracksCard(tracks: VodTracks, audio: Int?, subtitle: Int?, onAudio: (Int) -> Unit, onSubtitle: (Int?) -> Unit) {
    val focus = remember { FocusRequester() }
    val start = remember { tracksFocus(tracks, audio, subtitle) }
    fun focusFor(row: TracksFocus) = if (row == start) Modifier.focusRequester(focus) else Modifier
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus) }
    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .screenPadding()
            .width(420.dp)
            .background(SofaOverlayScrim, OverlayShape)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.vod_tracks_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (tracks.audio.size > 1) {
            Text(stringResource(Res.string.stream_card_audio), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tracks.audio.forEach { track ->
                TrackRow(track.label, track.index == audio, onClick = { onAudio(track.index) }, modifier = focusFor(TracksFocus.Audio(track.index)))
            }
        }
        if (tracks.subtitles.isNotEmpty()) {
            Text(stringResource(Res.string.vod_tracks_subtitles), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TrackRow(
                stringResource(Res.string.vod_tracks_subtitles_off),
                subtitle == null,
                onClick = { onSubtitle(null) },
                modifier = focusFor(TracksFocus.Subtitle(null)),
            )
            tracks.subtitles.forEach { track ->
                TrackRow(track.label, track.index == subtitle, onClick = { onSubtitle(track.index) }, modifier = focusFor(TracksFocus.Subtitle(track.index)))
            }
        }
    }
}

/** The subtitle on screen, centered [bottom] above the bottom edge's overscan. */
@Composable
internal fun BoxScope.SubtitleText(text: String, bottom: Dp) {
    Box(Modifier.align(Alignment.BottomCenter).screenPadding().padding(bottom = bottom)) {
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.background(SofaOverlayScrim, OverlayShape).padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
