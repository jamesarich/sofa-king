package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.theme.tvFocus

/**
 * One of [album]'s tracks as a row: its number, with its disc's when the album has [discs], its title with its own
 * artist under it when that differs from the album's, and its length. OK or the remote's Play key calls [onClick].
 */
@Composable
internal fun TrackRow(
    track: MediaItem,
    album: MediaItem,
    discs: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val number = trackNumber(track, discs)
    val artist = track.trackArtist?.takeIf { it != album.artist }
    val length = track.durationMs?.let(::formatPlayTime)
    val label = listOfNotNull(number, track.title, artist, length).joinToString(", ")
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth().playKey(onClick).tvFocus(CardDefaults.shape, focusedScale = 1f)
            .clip(CardDefaults.shape)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                number.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = NUMBER_WIDTH),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist != null) {
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (length != null) {
                Text(
                    length,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A track's number as its row shows it: `2-05` on an album of several [discs], `5` on one, or null when untagged.
 */
internal fun trackNumber(track: MediaItem, discs: Boolean): String? {
    val number = track.track ?: return null
    val disc = track.disc?.takeIf { discs } ?: return "$number"
    return "$disc-${number.toString().padStart(2, '0')}"
}

/** Whether [tracks] come from more than one disc. */
internal fun hasDiscs(tracks: List<MediaItem>): Boolean = tracks.mapNotNullTo(HashSet()) { it.disc }.size > 1

private val NUMBER_WIDTH = 40.dp
