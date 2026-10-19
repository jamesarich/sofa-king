package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import dev.jamesrich.sofaking.ui.player.PlayerIcons
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import dev.jamesrich.sofaking.ui.guide.HeldOkGuard
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaOverlayTrack
import dev.jamesrich.sofaking.ui.theme.tvFocus
import androidx.compose.runtime.LaunchedEffect
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A movie or show as a 2:3 poster with its title underneath: a bar for how far in it is, a tick once watched and
 * a count of unwatched episodes on a show. OK calls [onClick]; the remote's Play key calls [onPlay] for an item
 * that plays; a held OK, a long touch or a right click opens [menu].
 */
@Composable
internal fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    width: Dp = POSTER_WIDTH,
    menu: CardMenu? = null,
) {
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemCard(item, onClick, onPlay, menu, aspectRatio = 2f / 3f, focusScale = null) {
            // A file without a poster still has a frame from the server, cropped to the poster's shape.
            Artwork(item.poster ?: item.backdrop, label = null)
        }
        CardLabel(item.title, posterSubtitle(item))
    }
}

/** An episode, video or item in progress as a 16:9 card, its show and episode number under the title. */
@Composable
internal fun WideCard(
    item: MediaItem,
    onClick: () -> Unit,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    width: Dp = WIDE_WIDTH,
    menu: CardMenu? = null,
) {
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemCard(item, onClick, onPlay, menu, aspectRatio = 16f / 9f, focusScale = WIDE_FOCUS_SCALE) {
            Artwork(item.backdrop ?: item.poster, label = null)
        }
        if (item.kind == ItemKind.EPISODE) {
            CardLabel(item.showTitle ?: item.title, listOfNotNull(item.episodeLabel, item.title.takeIf { item.showTitle != null }).joinToString(" · "))
        } else {
            CardLabel(item.title, item.year?.toString())
        }
    }
}

/** What a card's menu does: play from the start (null when that is not offered) and mark watched or unwatched. */
internal class CardMenu(val onPlayFromStart: ((MediaItem) -> Unit)?, val onSetWatched: (MediaItem, Boolean) -> Unit)

/**
 * The card itself, with [menu] on a held OK. The rest of the press that opened it, its repeats and key-up, is
 * swallowed, so the menu's first entry is not pressed by the same OK.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(
    item: MediaItem,
    onClick: () -> Unit,
    onPlay: (() -> Unit)?,
    menu: CardMenu?,
    aspectRatio: Float,
    focusScale: Float?,
    picture: @Composable BoxScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val heldOk = remember { HeldOkGuard() }
    val focus = if (focusScale == null) Modifier.tvFocus(CardShape) else Modifier.tvFocus(CardShape, focusScale)
    Box {
        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio).playKey(onPlay).heldOk(heldOk).then(focus).clip(CardShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = if (menu != null) stringResource(Res.string.library_card_menu) else null,
                    onLongClick = menu?.let {
                        {
                            heldOk.arm()
                            open = true
                        }
                    },
                ),
        ) {
            Box(Modifier.fillMaxSize()) {
                picture()
                ItemMarks(item)
            }
        }
        if (menu != null) {
            val first = remember { FocusRequester() }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heldOk(heldOk)) {
                val entries = cardMenuEntries(item, playFromStart = menu.onPlayFromStart != null)
                entries.forEachIndexed { index, entry ->
                    DropdownMenuItem(
                        text = { Text(stringResource(entry.label)) },
                        onClick = {
                            open = false
                            when (entry) {
                                CardMenuEntry.PLAY_FROM_START -> menu.onPlayFromStart?.invoke(item)
                                CardMenuEntry.MARK_WATCHED -> menu.onSetWatched(item, true)
                                CardMenuEntry.MARK_UNWATCHED -> menu.onSetWatched(item, false)
                            }
                        },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
                LaunchedEffect(Unit) { requestFocusWhenAttached(first) }
            }
        }
    }
}

/** Passes OK's key-down and key-up through [guard], dropping the ones it swallows. */
private fun Modifier.heldOk(guard: HeldOkGuard): Modifier = onPreviewKeyEvent { event ->
    val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
    when {
        !ok -> false
        event.type == KeyEventType.KeyDown -> guard.onKeyDown()
        event.type == KeyEventType.KeyUp -> guard.onKeyUp()
        else -> false
    }
}

private val CardMenuEntry.label: StringResource
    get() = when (this) {
        CardMenuEntry.PLAY_FROM_START -> Res.string.library_play_from_start
        CardMenuEntry.MARK_WATCHED -> Res.string.library_mark_watched
        CardMenuEntry.MARK_UNWATCHED -> Res.string.library_mark_unwatched
    }

/**
 * The picture filling its card. Under it, and alone while it loads or when it fails, [label] stands in its place,
 * or the library mark when the card's own caption already names the item.
 */
@Composable
internal fun BoxScope.Artwork(url: String?, label: String?) {
    if (label == null) {
        Icon(
            PlayerIcons.VideoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.Center).size(48.dp),
        )
    } else {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(12.dp),
        )
    }
    if (url != null) AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
