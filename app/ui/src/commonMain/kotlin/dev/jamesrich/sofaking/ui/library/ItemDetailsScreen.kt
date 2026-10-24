package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.ui.WIDESCREEN
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.PrimaryAction
import dev.jamesrich.sofaking.data.fileFacts
import dev.jamesrich.sofaking.data.hidesSpoilers
import dev.jamesrich.sofaking.data.primaryAction
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_cast
import dev.jamesrich.sofaking.resources.library_directors
import dev.jamesrich.sofaking.resources.library_episodes
import dev.jamesrich.sofaking.resources.library_extras
import dev.jamesrich.sofaking.resources.library_go_to_show
import dev.jamesrich.sofaking.resources.library_load_failed
import dev.jamesrich.sofaking.resources.library_loading
import dev.jamesrich.sofaking.resources.library_mark_unwatched
import dev.jamesrich.sofaking.resources.library_mark_watched
import dev.jamesrich.sofaking.resources.library_play
import dev.jamesrich.sofaking.resources.library_play_episode
import dev.jamesrich.sofaking.resources.library_play_from_start
import dev.jamesrich.sofaking.resources.library_rating
import dev.jamesrich.sofaking.resources.library_related
import dev.jamesrich.sofaking.resources.library_resume
import dev.jamesrich.sofaking.resources.library_resume_episode
import dev.jamesrich.sofaking.resources.library_season
import dev.jamesrich.sofaking.resources.library_seasons
import dev.jamesrich.sofaking.resources.library_specials
import dev.jamesrich.sofaking.resources.library_spoiler_hidden
import dev.jamesrich.sofaking.resources.library_subtitles
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.tagline
import dev.jamesrich.sofaking.ui.Artwork
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.ScrollOnlyWhenHidden
import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.hoursAndMinutes
import dev.jamesrich.sofaking.ui.oneDecimal
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaChip
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * An item's page, after Plex: the backdrop behind the title, its year, length and ratings, the overview, then Play
 * or Resume (focused on arrival), Play from beginning and Mark watched. A show lists its seasons as chips with
 * [season]'s episodes under them, the season [selectedSeasonId] names; a season lists its own episodes. OK on an
 * episode plays it, resuming where it stopped. Under them are the item's Extras, which OK plays, and More like this,
 * which OK opens. [returnedFrom] is the episode or extra the viewer just watched, or the title like it whose page
 * they just left, which takes focus instead of the first button.
 */
@Composable
internal fun ItemDetailsScreen(
    state: ItemDetailState,
    season: ItemDetailState?,
    selectedSeasonId: String?,
    actions: ItemDetailsActions,
    returnedFrom: String? = null,
    hideSpoilers: Boolean = false,
) {
    when (state) {
        ItemDetailState.Loading -> MessageScreen(stringResource(Res.string.library_loading), loading = true)

        is ItemDetailState.Failed -> MessageScreen(
            stringResource(Res.string.library_load_failed),
            detail = state.message,
            onRetry = actions.onRetry,
        )

        is ItemDetailState.Ready -> Details(state.detail, season, selectedSeasonId, actions, returnedFrom, hideSpoilers)
    }
}

@Composable
private fun Details(
    detail: MediaItemDetail,
    season: ItemDetailState?,
    selectedSeasonId: String?,
    actions: ItemDetailsActions,
    returnedFrom: String?,
    hideSpoilers: Boolean,
) {
    val item = detail.item
    val insets = screenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical).asPaddingValues()
    val seasons = detail.children.filter { it.kind == ItemKind.SEASON }
    val episodes: List<MediaItem> = when {
        item.kind == ItemKind.SEASON -> detail.children.filter { it.kind.playable }

        seasons.isNotEmpty() -> ((season as? ItemDetailState.Ready)?.detail?.children).orEmpty().filter {
            it.kind.playable
        }

        else -> detail.children.filter { it.kind.playable }
    }
    val sides = insets.horizontalOnly()
    val primaryFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val returnTo = returnedFrom?.takeIf { id -> episodes.any { it.id == id } }
    val returnToExtra = returnedFrom?.takeIf { id -> detail.extras.any { it.id == id } }
    val returnToRelated = returnedFrom?.takeIf { id -> detail.related.any { it.id == id } }
    // The first button takes focus once, when the page is in; a later refresh leaves focus where the viewer put it.
    LaunchedEffect(item.id) { requestFocusWhenAttached(primaryFocus) }
    LaunchedEffect(returnTo) { if (returnTo != null) requestFocusWhenAttached(episodeFocus) }
    LaunchedEffect(returnToExtra) { if (returnToExtra != null) requestFocusWhenAttached(episodeFocus) }
    LaunchedEffect(returnToRelated) { if (returnToRelated != null) requestFocusWhenAttached(episodeFocus) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * HERO_FRACTION
        Hero(item.backdrop ?: item.poster, Modifier.fillMaxWidth().height(heroHeight))
        // Focus scrolls only to show what it lands on, so arriving on Play keeps the title above it on screen.
        ScrollOnlyWhenHidden(FOCUS_EDGE) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = (heroHeight * 0.3f).coerceAtLeast(insets.calculateTopPadding()),
                    bottom = insets.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "summary") { Summary(detail, actions, primaryFocus, hideSpoilers, Modifier.padding(sides)) }
                if (detail.cast.isNotEmpty()) {
                    item(key = "cast") { Cast(detail.cast.take(CAST_SHOWN), actions.onPerson, sides) }
                }
                if (seasons.size > 1) {
                    item(key = "seasons") { Seasons(seasons, selectedSeasonId, actions.onSelectSeason, sides) }
                }
                if (seasons.isNotEmpty() && season is ItemDetailState.Failed) {
                    item(key = "season-failed") {
                        Text(
                            season.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(sides),
                        )
                    }
                }
                items(episodes, key = { it.id }) { episode ->
                    val modifier = if (episode.id == returnTo) Modifier.focusRequester(episodeFocus) else Modifier
                    EpisodeRow(episode, hideSpoilers && hidesSpoilers(episode), onClick = {
                        actions.onPlay(episode, false)
                    }, modifier = Modifier.padding(sides).then(modifier))
                }
                if (detail.extras.isNotEmpty()) {
                    item(key = "extras") {
                        ItemRow(
                            stringResource(Res.string.library_extras),
                            detail.extras,
                            CardStyle.WIDE,
                            sides,
                            onOpen = { actions.onPlay(it, false) },
                            onPlay = { actions.onPlay(it, false) },
                            focusItem = returnToExtra,
                            focusRequester = episodeFocus,
                        )
                    }
                }
                if (detail.related.isNotEmpty()) {
                    item(key = "related") {
                        val style = if (detail.related.all {
                                it.kind == ItemKind.VIDEO
                            }
                        ) {
                            CardStyle.WIDE
                        } else {
                            CardStyle.POSTER
                        }
                        ItemRow(
                            stringResource(
                                Res.string.library_related,
                            ),
                            detail.related,
                            style,
                            sides,
                            onOpen = actions.onOpenItem,
                            onPlay = {
                                actions.onPlay(it, false)
                            },
                            focusItem = returnToRelated,
                            focusRequester = episodeFocus,
                        )
                    }
                }
            }
        }
    }
}

/** How far clear of the screen's edges, outside the overscan, focus keeps what it lands on. */
private val FOCUS_EDGE = 48.dp

/** The seasons in one row; focus coming down from the buttons lands on the season being shown. */
@Composable
private fun Seasons(
    seasons: List<MediaItem>,
    selectedSeasonId: String?,
    onSelect: (String) -> Unit,
    sides: PaddingValues,
) {
    val selected = seasons.indexOfFirst { it.id == selectedSeasonId }.coerceAtLeast(0)
    val selectedFocus = remember { FocusRequester() }
    LazyRow(
        state = rememberLazyListState(initialFirstVisibleItemIndex = selected),
        contentPadding = sides,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.focusRestorer(selectedFocus),
    ) {
        itemsIndexed(seasons, key = { _, season -> season.id }) { index, season ->
            SofaChip(
                selected = season.id == selectedSeasonId,
                onClick = { onSelect(season.id) },
                label = seasonLabel(season),
                modifier = if (index == selected) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }
    }
}

/** The backdrop at the top right, fading into the page to the left and below so text over it reads. */
@Composable
private fun Hero(url: String?, modifier: Modifier = Modifier) {
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
private fun Summary(
    detail: MediaItemDetail,
    actions: ItemDetailsActions,
    primaryFocus: FocusRequester,
    hideSpoilers: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = detail.item
    Column(modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item.kind == ItemKind.EPISODE && item.showTitle != null) {
            Text(
                listOfNotNull(item.showTitle, item.episodeLabel).joinToString(DETAIL_SEPARATOR),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
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
            shape = OverviewShape,
            color = Color.Transparent,
            modifier = Modifier.tvFocus(
                OverviewShape,
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
private val OverviewShape = RoundedCornerShape(8.dp)

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

/**
 * A titled row of cards: OK on one calls [onOpen], the remote's Play key [onPlay]; [focusItem] takes [focusRequester].
 */
@Composable
private fun ItemRow(
    title: String,
    items: List<MediaItem>,
    style: CardStyle,
    sides: PaddingValues,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    focusItem: String? = null,
    focusRequester: FocusRequester? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title, sides)
        LazyRow(
            contentPadding = sides,
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            modifier = Modifier.focusRestorer(),
        ) {
            items(items, key = { it.id }) { item ->
                val focus = if (item.id == focusItem &&
                    focusRequester != null
                ) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
                LibraryCard(item, style, onOpen, onPlay, menu = null, modifier = focus)
            }
        }
    }
}

/** The cast as chips; OK on one searches the libraries for what else they are in. */
@Composable
private fun Cast(cast: List<String>, onPerson: (String) -> Unit, sides: PaddingValues) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.library_cast),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(sides),
        )
        LazyRow(
            contentPadding = sides,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            items(cast, key = {
                it
            }) { person -> SofaChip(selected = false, onClick = { onPerson(person) }, label = person) }
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
        SofaButton(
            onClick = { actions.onSetWatched(item, !watched) },
            modifier = if (action == null) Modifier.focusRequester(primaryFocus) else Modifier,
        ) {
            Text(stringResource(if (watched) Res.string.library_mark_unwatched else Res.string.library_mark_watched))
        }
        val showId = item.showId?.takeIf { item.kind == ItemKind.EPISODE || item.kind == ItemKind.SEASON }
        if (showId != null) {
            SofaButton(onClick = { actions.onOpenShow(showId) }) { Text(stringResource(Res.string.library_go_to_show)) }
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

        is PrimaryAction.Play -> if (episode != null) {
            stringResource(Res.string.library_play_episode, episode)
        } else {
            stringResource(Res.string.library_play)
        }
    }
}

/**
 * An episode as a row: its picture with progress, number and title, length and air date, and the overview; [spoilers]
 * hidden, the picture is blurred and the overview says only that the episode is unwatched.
 */
@Composable
private fun EpisodeRow(
    episode: MediaItem,
    spoilersHidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = RowShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth().playKey(onClick).tvFocus(RowShape, focusedScale = 1f),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(
                    EPISODE_THUMB_WIDTH,
                ).aspectRatio(WIDESCREEN).background(MaterialTheme.colorScheme.surfaceVariant, CardShape),
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    }
}

/** Where an overview would be, with Hide spoilers on: says only that the episode is unwatched. */
@Composable
private fun SpoilerHidden() {
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
        modifier = Modifier.border(1.dp, outline, BadgeShape).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun seasonLabel(season: MediaItem): String = when (val number = season.season) {
    null -> season.title
    0 -> stringResource(Res.string.library_specials)
    else -> stringResource(Res.string.library_season, number)
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
private const val HERO_FRACTION = 0.6f
private const val CAST_SHOWN = 6
private val EPISODE_THUMB_WIDTH = 176.dp
private val RowShape = RoundedCornerShape(12.dp)
private val BadgeShape = RoundedCornerShape(4.dp)
