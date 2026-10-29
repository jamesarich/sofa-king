package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.music_next
import dev.jamesrich.sofaking.ui.Artwork
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The song [music] plays, in a bar under the pages while the viewer browses: its cover, title and artist, play or
 * pause and next, and how far through it is. A click opens Now Playing ([onOpen]).
 */
@Composable
internal fun MiniPlayer(music: MusicPlayback, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val song = music.song ?: return
    val status by music.status.collectAsStateWithLifecycle()
    val position = rememberMusicPosition(music)
    val length = music.durationMs
    Column(modifier.fillMaxWidth()) {
        if (length != null && length > 0) {
            LinearProgressIndicator(
                progress = { (position.toFloat() / length).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
        }
        ListItem(
            onClick = onOpen,
            supportingContent = songLine(song)?.let { line ->
                { Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            leadingContent = {
                Box(
                    Modifier.size(COVER_SIZE).clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Artwork(song.poster, label = null)
                }
            },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(BAR_ROW_SPACING),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayPauseControl(
                        paused = !status.playWhenReady || music.held,
                        failed = music.failure != null,
                        onPlayPause = music::togglePause,
                        onRetry = music::togglePause,
                    )
                    ControlIcon(SofaIcons.SkipNext, stringResource(Res.string.music_next), music::next)
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

private val COVER_SIZE = 48.dp
