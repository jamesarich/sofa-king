package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_spoiler_hidden
import dev.jamesrich.sofaking.ui.Artwork
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.WIDESCREEN
import dev.jamesrich.sofaking.ui.hoursAndMinutes
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource

/**
 * An episode as a row: its picture with progress, number and title, length and air date, and the overview; [spoilers]
 * hidden, the picture is blurred and the overview says only that the episode is unwatched. OK plays it; holding OK (a
 * long touch or a right click) opens [menu]: Details, Play from beginning when partway in, and the watched toggle.
 */
@Composable
internal fun EpisodeRow(
    episode: MediaItem,
    spoilersHidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: CardMenu? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Card(
            shape = RowShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().playKey(onClick).tvFocus(RowShape, focusedScale = 1f).clip(RowShape)
                .clickOrHoldForMenu(onClick, menu?.let { { open = true } }),
        ) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EpisodeThumb(episode, spoilersHidden)
                EpisodeText(episode, spoilersHidden, Modifier.weight(1f))
            }
        }
        if (menu != null) CardMenuDropdown(episode, menu, open, onClose = { open = false })
    }
}

/** The episode's still, with its watched tick and progress. */
@Composable
private fun EpisodeThumb(episode: MediaItem, spoilersHidden: Boolean) {
    Box(
        Modifier.width(EPISODE_THUMB_WIDTH).aspectRatio(WIDESCREEN)
            .background(MaterialTheme.colorScheme.surfaceVariant, CardShape),
    ) {
        Artwork(
            episode.stillOrBackdrop(),
            label = episode.episode?.let {
                "E$it"
            } ?: episode.title,
            hidden = spoilersHidden,
        )
        val progress = episode.progress
        if (progress?.watched == true) WatchedTick(Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (progress != null && progress.inProgress) {
            progress.fraction?.let { ProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/** The episode's number and title, length and air date, and overview unless it is a spoiler. */
@Composable
private fun EpisodeText(episode: MediaItem, spoilersHidden: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            listOfNotNull(episode.episode?.let { "$it." }, episode.title).joinToString(" "),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val line = listOfNotNull(
            runtime(episode.durationMs)?.hoursAndMinutes(),
            episode.aired,
        ).joinToString(DETAIL_SEPARATOR)
        if (line.isNotEmpty()) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        episode.overview?.let { overview ->
            if (spoilersHidden) {
                SpoilerHidden()
            } else {
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Where an overview would be, with Hide spoilers on: says only that the episode is unwatched. */
@Composable
internal fun SpoilerHidden() {
    Text(
        stringResource(Res.string.library_spoiler_hidden),
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** An episode's own still, which the server sends as its poster; its backdrop falls back to the show's. */
internal fun MediaItem.stillOrBackdrop(): String? = if (kind == ItemKind.EPISODE) {
    poster ?: backdrop
} else {
    backdrop ?: poster
}

private val EPISODE_THUMB_WIDTH = 176.dp
private val RowShape = RoundedCornerShape(12.dp)
