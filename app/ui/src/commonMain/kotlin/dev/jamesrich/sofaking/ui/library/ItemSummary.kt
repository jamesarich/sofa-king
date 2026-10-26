package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.PrimaryAction
import dev.jamesrich.sofaking.data.fileFacts
import dev.jamesrich.sofaking.data.hidesSpoilers
import dev.jamesrich.sofaking.data.primaryAction
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_add_to_watchlist
import dev.jamesrich.sofaking.resources.library_directors
import dev.jamesrich.sofaking.resources.library_episodes
import dev.jamesrich.sofaking.resources.library_go_to_artist
import dev.jamesrich.sofaking.resources.library_go_to_show
import dev.jamesrich.sofaking.resources.library_mark_unwatched
import dev.jamesrich.sofaking.resources.library_mark_watched
import dev.jamesrich.sofaking.resources.library_play
import dev.jamesrich.sofaking.resources.library_play_episode
import dev.jamesrich.sofaking.resources.library_play_from_start
import dev.jamesrich.sofaking.resources.library_rating
import dev.jamesrich.sofaking.resources.library_remove_from_watchlist
import dev.jamesrich.sofaking.resources.library_resume
import dev.jamesrich.sofaking.resources.library_resume_episode
import dev.jamesrich.sofaking.resources.library_seasons
import dev.jamesrich.sofaking.resources.library_subtitles
import dev.jamesrich.sofaking.resources.library_tracks
import dev.jamesrich.sofaking.resources.tagline
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.announcesChanges
import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.hoursAndMinutes
import dev.jamesrich.sofaking.ui.oneDecimal
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The backdrop at the top right, fading into the page to the left and below so text over it reads. */
@Composable
internal fun Hero(url: String?, modifier: Modifier = Modifier) {
    if (url == null) return
    val page = MaterialTheme.colorScheme.background
    Box(modifier) {
        AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to page,
                    BACKDROP_FADE_AT to page.copy(alpha = BACKDROP_FADE_ALPHA),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(BACKDROP_FLOOR_AT to Color.Transparent, 1f to page)),
        )
    }
}

@Composable
internal fun Summary(
    detail: MediaItemDetail,
    actions: ItemDetailsActions,
    primaryFocus: FocusRequester,
    hideSpoilers: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = detail.item
    Column(modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val over = when {
            item.kind == ItemKind.EPISODE && item.showTitle != null ->
                listOfNotNull(item.showTitle, item.episodeLabel).joinToString(DETAIL_SEPARATOR)

            item.kind == ItemKind.ALBUM || item.kind == ItemKind.TRACK -> item.artist

            else -> null
        }
        if (over != null) {
            Text(over, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(item.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MetaLine(detail)
        FileLine(detail)
        detail.tagline?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item.overview?.let { overview ->
            if (hideSpoilers && hidesSpoilers(item)) {
                SpoilerHidden()
            } else {
                Overview(overview)
            }
        }
        Buttons(detail, actions, primaryFocus)
        if (detail.directors.isNotEmpty()) {
            Credit(
                stringResource(Res.string.library_directors, detail.directors.joinToString(", ")),
            )
        }
    }
}

@Composable
private fun Buttons(detail: MediaItemDetail, actions: ItemDetailsActions, primaryFocus: FocusRequester) {
    val item = detail.item
    val action = primaryAction(detail)
    val watched = isWatched(item)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (action != null) {
            SofaButton(onClick = {
                actions.onPlay(action.item, false)
            }, modifier = Modifier.focusRequester(primaryFocus)) {
                Text(primaryLabel(action, playsItself = action.item.id == item.id))
            }
            if (action is PrimaryAction.Resume) {
                SofaButton(onClick = {
                    actions.onPlay(action.item, true)
                }) { Text(stringResource(Res.string.library_play_from_start)) }
            }
        }
        if (!item.kind.music) {
            SofaButton(
                onClick = { actions.onSetWatched(item, !watched) },
                modifier = (if (action == null) Modifier.focusRequester(primaryFocus) else Modifier).announcesChanges(),
            ) {
                Text(
                    stringResource(if (watched) Res.string.library_mark_unwatched else Res.string.library_mark_watched),
                )
            }
        }
        val listed = actions.watchlisted(item)?.takeIf { item.kind in WATCHLIST_KINDS }
        if (listed != null) {
            SofaButton(onClick = { actions.onToggleWatchlist(item) }, modifier = Modifier.announcesChanges()) {
                Text(
                    stringResource(
                        if (listed) Res.string.library_remove_from_watchlist else Res.string.library_add_to_watchlist,
                    ),
                )
            }
        }
        val showId = item.showId?.takeIf { item.kind == ItemKind.EPISODE || item.kind == ItemKind.SEASON }
        if (showId != null) {
            SofaButton(onClick = { actions.onOpenShow(showId) }) { Text(stringResource(Res.string.library_go_to_show)) }
        }
        val artistId = item.artistId?.takeIf { item.kind == ItemKind.ALBUM || item.kind == ItemKind.TRACK }
        if (artistId != null) {
            SofaButton(
                onClick = { actions.onOpenShow(artistId) },
                modifier = if (action == null) Modifier.focusRequester(primaryFocus) else Modifier,
            ) { Text(stringResource(Res.string.library_go_to_artist)) }
        }
    }
}

/** What the watchlist takes: what a library lists at its top, not a season or an episode. */
private val WATCHLIST_KINDS = setOf(ItemKind.MOVIE, ItemKind.SHOW, ItemKind.VIDEO)

@Composable
private fun primaryLabel(action: PrimaryAction, playsItself: Boolean): String {
    val episode = action.item.episodeLabel?.takeIf { !playsItself }
    return when (action) {
        is PrimaryAction.Resume -> if (episode != null) {
            stringResource(Res.string.library_resume_episode, episode)
        } else {
            stringResource(Res.string.library_resume, formatPlayTime(action.positionMs))
        }

        is PrimaryAction.Play -> if (episode != null) {
            stringResource(Res.string.library_play_episode, episode)
        } else {
            stringResource(Res.string.library_play)
        }
    }
}

/**
 * The overview, cut at [OVERVIEW_LINES]. A cut one takes focus, so a remote can reach it, and OK shows the rest or
 * folds it again.
 */
@Composable
private fun Overview(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var cut by remember { mutableStateOf(false) }
    val body: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else OVERVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) cut = it.hasVisualOverflow },
            modifier = modifier,
        )
    }
    if (cut || expanded) {
        Surface(
            onClick = {
                expanded = !expanded
            },
            shape = MaterialTheme.shapes.small,
            color = Color.Transparent,
            modifier = Modifier.tvFocus(
                MaterialTheme.shapes.small,
                focusedScale = 1f,
            ),
        ) {
            body(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    } else {
        body(Modifier)
    }
}

private const val OVERVIEW_LINES = 4

/** Year, length or seasons, rating and the picture's badges on one line. */
@Composable
private fun MetaLine(detail: MediaItemDetail) {
    val item = detail.item
    val seasons = detail.children.count { it.kind == ItemKind.SEASON }
    val parts = buildList {
        item.year?.let { add(it.toString()) }
        when {
            seasons > 0 -> add(pluralStringResource(Res.plurals.library_seasons, seasons, seasons))

            item.kind == ItemKind.SEASON -> add(
                pluralStringResource(Res.plurals.library_episodes, detail.children.size, detail.children.size),
            )

            item.kind == ItemKind.ALBUM -> {
                add(pluralStringResource(Res.plurals.library_tracks, detail.children.size, detail.children.size))
                runtime(detail.children.sumOf { it.durationMs ?: 0 })?.let { add(it.hoursAndMinutes()) }
            }

            else -> runtime(item.durationMs)?.let { add(it.hoursAndMinutes()) }
        }
        item.rating?.let { add(stringResource(Res.string.library_rating, oneDecimal(it))) }
        if (item.genres.isNotEmpty()) add(item.genres.take(3).joinToString(", "))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            parts.joinToString(DETAIL_SEPARATOR),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        item.contentRating?.let { OutlinedBadge(it) }
        pictureBadge(item.videoHeight)?.let { OutlinedBadge(it) }
        if (item.hdr) OutlinedBadge(HDR)
        if (item.progress?.watched == true) WatchedTick()
    }
}

/** The file's sound, its audio and subtitle languages, and the studio or network, on one line. */
@Composable
private fun FileLine(detail: MediaItemDetail) {
    val facts = fileFacts(detail)
    val parts = buildList {
        facts.sound?.let(::add)
        if (facts.audioLanguages.isNotEmpty()) add(facts.audioLanguages.joinToString(", "))
        if (facts.subtitleLanguages.isNotEmpty()) {
            add(
                stringResource(Res.string.library_subtitles, facts.subtitleLanguages.joinToString(", ")),
            )
        }
        detail.studio?.let(::add)
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(DETAIL_SEPARATOR),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Credit(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun OutlinedBadge(text: String) {
    val outline = MaterialTheme.colorScheme.outline
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.border(
            1.dp,
            outline,
            MaterialTheme.shapes.extraSmall,
        ).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The picture's size as the badge the live info bar would show; null below HD or when unknown. */
internal fun pictureBadge(height: Int?): String? = when {
    height == null -> null
    height >= UHD_HEIGHT -> "4K"
    height >= HD_HEIGHT -> "HD"
    else -> null
}

private const val HDR = "HDR"

/** The lowest picture heights shown as 4K (a 2160-line picture cropped to scope still is) and as HD. */
private const val UHD_HEIGHT = 2000
private const val HD_HEIGHT = 720

/**
 * The backdrop's fades into the page: across, from solid at the left to [BACKDROP_FADE_ALPHA] at [BACKDROP_FADE_AT]
 * and clear at the right, and down, from clear at [BACKDROP_FLOOR_AT] to solid at the bottom.
 */
private const val BACKDROP_FADE_AT = 0.55f
private const val BACKDROP_FADE_ALPHA = 0.6f
private const val BACKDROP_FLOOR_AT = 0.4f
