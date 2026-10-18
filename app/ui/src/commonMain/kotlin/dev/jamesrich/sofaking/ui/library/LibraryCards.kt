package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaOverlayTrack
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource

/**
 * A movie or show as a 2:3 poster with its title underneath: a bar for how far in it is, a tick once watched and
 * a count of unwatched episodes on a show. OK calls [onClick]; the remote's Play key calls [onPlay] for an item
 * that plays.
 */
@Composable
internal fun PosterCard(item: MediaItem, onClick: () -> Unit, onPlay: (() -> Unit)?, modifier: Modifier = Modifier, width: Dp = POSTER_WIDTH) {
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).playKey(onPlay).tvFocus(CardShape),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(item.poster, item.title)
                ItemMarks(item)
            }
        }
        CardLabel(item.title, posterSubtitle(item))
    }
}

/** An episode, video or item in progress as a 16:9 card, its show and episode number under the title. */
@Composable
internal fun WideCard(item: MediaItem, onClick: () -> Unit, onPlay: (() -> Unit)?, modifier: Modifier = Modifier, width: Dp = WIDE_WIDTH) {
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).playKey(onPlay).tvFocus(CardShape, WIDE_FOCUS_SCALE),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(item.backdrop ?: item.poster, item.showTitle ?: item.title)
                ItemMarks(item)
            }
        }
        if (item.kind == ItemKind.EPISODE) {
            CardLabel(item.showTitle ?: item.title, listOfNotNull(item.episodeLabel, item.title.takeIf { item.showTitle != null }).joinToString(" · "))
        } else {
            CardLabel(item.title, item.year?.toString())
        }
    }
}

/** The picture filling its card, or the title in its place while there is none. */
@Composable
internal fun BoxScope.Artwork(url: String?, title: String) {
    if (url == null) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(12.dp),
        )
    } else {
        AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The progress bar along the bottom, the watched tick and a show's unwatched count. */
@Composable
private fun BoxScope.ItemMarks(item: MediaItem) {
    val progress = item.progress
    when {
        progress?.watched == true -> WatchedTick(Modifier.align(Alignment.TopEnd).padding(8.dp))
        item.unwatched > 0 && (item.kind == ItemKind.SHOW || item.kind == ItemKind.SEASON) -> Text(
            item.unwatched.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(MaterialTheme.colorScheme.primary, BadgeShape).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
    if (progress != null && progress.inProgress) progress.fraction?.let { ProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
}

/** How far into an item playback is, as a thin bar across the bottom of its picture. */
@Composable
internal fun ProgressStrip(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction },
        color = MaterialTheme.colorScheme.primary,
        trackColor = SofaOverlayTrack,
        drawStopIndicator = {},
        gapSize = 0.dp,
        modifier = modifier.fillMaxWidth().height(4.dp),
    )
}

@Composable
internal fun WatchedTick(modifier: Modifier = Modifier) {
    Box(modifier.size(28.dp).background(SofaOverlayScrim, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.library_watched), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CardLabel(title: String, subtitle: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            subtitle.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A poster's second line: a show's season or episode count would need the details, so the year, or the episode number. */
private fun posterSubtitle(item: MediaItem): String? = item.episodeLabel ?: item.year?.toString()

/** The remote's Play and Play/Pause keys start [onPlay], as Plex does on its cards; OK still opens the details. */
internal fun Modifier.playKey(onPlay: (() -> Unit)?): Modifier = if (onPlay == null) this else onPreviewKeyEvent { event ->
    val play = event.key == Key.MediaPlay || event.key == Key.MediaPlayPause
    if (play && event.type == KeyEventType.KeyUp) onPlay()
    play
}

internal val CardShape = RoundedCornerShape(12.dp)
private val BadgeShape = RoundedCornerShape(8.dp)

/** About six posters across a 1080p set inside its overscan. */
internal val POSTER_WIDTH = 132.dp

/** About four wide cards across. */
internal val WIDE_WIDTH = 200.dp
internal val CARD_GAP = 16.dp

/** Wide cards grow less than posters on focus, as Google scales by size. */
private const val WIDE_FOCUS_SCALE = 1.05f
