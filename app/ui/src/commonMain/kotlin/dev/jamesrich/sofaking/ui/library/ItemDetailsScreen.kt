package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.PrimaryAction
import dev.jamesrich.sofaking.data.primaryAction
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.guide.GuideChip
import dev.jamesrich.sofaking.ui.player.formatPlayTime
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource

/** What an item's page does. [onPlay] resumes an item in progress unless [fromStart] is set. */
internal class ItemDetailsActions(
    val onPlay: (item: MediaItem, fromStart: Boolean) -> Unit,
    val onSetWatched: (MediaItem, Boolean) -> Unit,
    val onSelectSeason: (String) -> Unit,
    val onRetry: () -> Unit,
)

/**
 * An item's page, after Plex: the backdrop behind the title, its year, length and ratings, the overview, then Play
 * or Resume (focused on arrival), Play from beginning and Mark watched. A show lists its seasons as chips with
 * [season]'s episodes under them, the season [selectedSeasonId] names; a season lists its own episodes. OK on an
 * episode plays it, resuming where it stopped. [returnedFrom] is the episode the viewer just watched, which takes
 * focus instead of the first button.
 */
@Composable
internal fun ItemDetailsScreen(
    state: ItemDetailState,
    season: ItemDetailState?,
    selectedSeasonId: String?,
    actions: ItemDetailsActions,
    returnedFrom: String? = null,
) {
    when (state) {
        ItemDetailState.Loading -> MessageScreen(stringResource(Res.string.library_loading), loading = true)
        is ItemDetailState.Failed -> MessageScreen(stringResource(Res.string.library_load_failed), detail = state.message, onRetry = actions.onRetry)
        is ItemDetailState.Ready -> Details(state.detail, season, selectedSeasonId, actions, returnedFrom)
    }
}

@Composable
private fun Details(detail: MediaItemDetail, season: ItemDetailState?, selectedSeasonId: String?, actions: ItemDetailsActions, returnedFrom: String?) {
    val item = detail.item
    val insets = screenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical).asPaddingValues()
    val seasons = detail.children.filter { it.kind == ItemKind.SEASON }
    val episodes: List<MediaItem> = when {
        item.kind == ItemKind.SEASON -> detail.children.filter { it.kind.playable }
        seasons.isNotEmpty() -> ((season as? ItemDetailState.Ready)?.detail?.children).orEmpty().filter { it.kind.playable }
        else -> detail.children.filter { it.kind.playable }
    }
    val direction = LocalLayoutDirection.current
    val primaryFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val returnTo = returnedFrom?.takeIf { id -> episodes.any { it.id == id } }
    // The first button takes focus once, when the page is in; a later refresh leaves focus where the viewer put it.
    LaunchedEffect(item.id) { requestFocusWhenAttached(primaryFocus) }
    LaunchedEffect(returnTo) { if (returnTo != null) requestFocusWhenAttached(episodeFocus) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * HERO_FRACTION
        Hero(item.backdrop ?: item.poster, Modifier.fillMaxWidth().height(heroHeight))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = insets.calculateStartPadding(direction),
                end = insets.calculateEndPadding(direction),
                top = (heroHeight * 0.3f).coerceAtLeast(insets.calculateTopPadding()),
                bottom = insets.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "summary") { Summary(detail, actions, primaryFocus) }
            if (seasons.size > 1) {
                item(key = "seasons") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        seasons.forEach { s ->
                            GuideChip(
                                selected = s.id == selectedSeasonId,
                                onClick = { actions.onSelectSeason(s.id) },
                                label = seasonLabel(s),
                            )
                        }
                    }
                }
            }
            if (seasons.isNotEmpty() && season is ItemDetailState.Failed) {
                item(key = "season-failed") {
                    Text(season.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(episodes, key = { it.id }) { episode ->
                val modifier = if (episode.id == returnTo) Modifier.focusRequester(episodeFocus) else Modifier
                EpisodeRow(episode, onClick = { actions.onPlay(episode, false) }, modifier = modifier)
            }
        }
    }
}

/** The backdrop at the top right, fading into the page to the left and below so text over it reads. */
@Composable
private fun Hero(url: String?, modifier: Modifier) {
    if (url == null) return
    val page = MaterialTheme.colorScheme.background
    Box(modifier) {
        AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to page, 0.55f to page.copy(alpha = 0.6f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to page)))
    }
}

@Composable
private fun Summary(detail: MediaItemDetail, actions: ItemDetailsActions, primaryFocus: FocusRequester) {
    val item = detail.item
    Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item.kind == ItemKind.EPISODE && item.showTitle != null) {
            Text(
                listOfNotNull(item.showTitle, item.episodeLabel).joinToString(DETAIL_SEPARATOR),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(item.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MetaLine(detail)
        detail.tagline?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        Buttons(detail, actions, primaryFocus)
        if (detail.directors.isNotEmpty()) Credit(stringResource(Res.string.library_directors, detail.directors.joinToString(", ")))
        if (detail.cast.isNotEmpty()) Credit(stringResource(Res.string.library_cast, detail.cast.take(CAST_SHOWN).joinToString(", ")))
    }
}

/** Year, length or seasons, rating and the picture's badges on one line. */
@Composable
private fun MetaLine(detail: MediaItemDetail) {
    val item = detail.item
    val seasons = detail.children.count { it.kind == ItemKind.SEASON }
    val parts = buildList {
        item.year?.let { add(it.toString()) }
        when {
            seasons > 0 -> add(stringResource(Res.string.library_seasons, seasons))
            item.kind == ItemKind.SEASON -> add(stringResource(Res.string.library_episodes, detail.children.size))
            else -> runtimeText(item.durationMs)?.let(::add)
        }
        item.rating?.let { add(stringResource(Res.string.library_rating, oneDecimal(it))) }
        if (item.genres.isNotEmpty()) add(item.genres.take(3).joinToString(", "))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(parts.joinToString(DETAIL_SEPARATOR), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        item.contentRating?.let { OutlinedBadge(it) }
        pictureBadge(item.videoHeight)?.let { OutlinedBadge(it) }
        if (item.hdr) OutlinedBadge(HDR)
        if (item.progress?.watched == true) WatchedTick()
    }
}

@Composable
private fun Buttons(detail: MediaItemDetail, actions: ItemDetailsActions, primaryFocus: FocusRequester) {
    val item = detail.item
    val action = primaryAction(detail)
    val watched = when (item.kind) {
        ItemKind.SHOW, ItemKind.SEASON -> item.unwatched == 0 && item.children > 0
        else -> item.progress?.watched == true
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (action != null) {
            SofaButton(onClick = { actions.onPlay(action.item, false) }, modifier = Modifier.focusRequester(primaryFocus)) {
                Text(primaryLabel(action, playsItself = action.item.id == item.id))
            }
            if (action is PrimaryAction.Resume) {
                SofaButton(onClick = { actions.onPlay(action.item, true) }) { Text(stringResource(Res.string.library_play_from_start)) }
            }
        }
        SofaButton(
            onClick = { actions.onSetWatched(item, !watched) },
            modifier = if (action == null) Modifier.focusRequester(primaryFocus) else Modifier,
        ) {
            Text(stringResource(if (watched) Res.string.library_mark_unwatched else Res.string.library_mark_watched))
        }
    }
}

@Composable
private fun primaryLabel(action: PrimaryAction, playsItself: Boolean): String {
    val episode = action.item.episodeLabel?.takeIf { !playsItself }
    return when (action) {
        is PrimaryAction.Resume -> if (episode != null) {
            stringResource(Res.string.library_resume_episode, episode)
        } else {
            stringResource(Res.string.library_resume, formatPlayTime(action.positionMs))
        }
        is PrimaryAction.Play -> if (episode != null) stringResource(Res.string.library_play_episode, episode) else stringResource(Res.string.library_play)
    }
}

/** An episode as a row: its picture with progress, number and title, length and air date, and the overview. */
@Composable
private fun EpisodeRow(episode: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        shape = RowShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth().playKey(onClick).tvFocus(RowShape, focusedScale = 1f),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(EPISODE_THUMB_WIDTH).aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant, CardShape)) {
                Artwork(episode.backdrop ?: episode.poster, episode.episode?.let { "E$it" } ?: episode.title)
                val progress = episode.progress
                if (progress?.watched == true) WatchedTick(Modifier.align(Alignment.TopEnd).padding(6.dp))
                if (progress != null && progress.inProgress) progress.fraction?.let { ProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOfNotNull(episode.episode?.let { "$it." }, episode.title).joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = listOfNotNull(runtimeText(episode.durationMs), episode.aired).joinToString(DETAIL_SEPARATOR)
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                episode.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun Credit(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun OutlinedBadge(text: String) {
    val outline = MaterialTheme.colorScheme.outline
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.border(1.dp, outline, BadgeShape).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun seasonLabel(season: MediaItem): String = when (val number = season.season) {
    null -> season.title
    0 -> stringResource(Res.string.library_specials)
    else -> stringResource(Res.string.library_season, number)
}

@Composable
private fun runtimeText(durationMs: Long?): String? {
    val (hours, minutes) = runtimeParts(durationMs) ?: return null
    return if (hours > 0) stringResource(Res.string.library_hours_minutes, hours, minutes) else stringResource(Res.string.library_minutes, minutes)
}

/** The picture's size as the badge the live info bar would show; null below HD or when unknown. */
internal fun pictureBadge(height: Int?): String? = when {
    height == null -> null
    height >= 2000 -> "4K"
    height >= 720 -> "HD"
    else -> null
}

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

private const val HDR = "HDR"
private const val HERO_FRACTION = 0.6f
private const val CAST_SHOWN = 6
private val EPISODE_THUMB_WIDTH = 176.dp
private val RowShape = RoundedCornerShape(12.dp)
private val BadgeShape = RoundedCornerShape(4.dp)
