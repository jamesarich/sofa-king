package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.hidesSpoilers
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_cast
import dev.jamesrich.sofaking.resources.library_extras
import dev.jamesrich.sofaking.resources.library_load_failed
import dev.jamesrich.sofaking.resources.library_loading
import dev.jamesrich.sofaking.resources.library_related
import dev.jamesrich.sofaking.resources.library_season
import dev.jamesrich.sofaking.resources.library_specials
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.ScrollOnlyWhenHidden
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import dev.jamesrich.sofaking.ui.theme.SofaChip
import org.jetbrains.compose.resources.stringResource

/**
 * An item's page, after Plex: the backdrop behind the title, its year, length and ratings, the overview, then Play
 * or Resume (focused on arrival), Play from beginning and Mark watched. A show lists its seasons as chips with
 * [season]'s episodes under them, the season [selectedSeasonId] names; a season lists its own episodes. OK on an
 * episode plays it, resuming where it stopped; holding OK opens a menu with Details (its own page), Play from beginning
 * and the watched toggle. Under them are the item's Extras, which OK plays, and More like this, which OK opens.
 * [returnedFrom] is the episode or extra the viewer just watched, or the title like it whose page
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
    val episodes = episodesOf(detail, seasons, season)
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

    val episodeMenu = episodeMenu(actions)
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
                    }, menu = episodeMenu, modifier = Modifier.padding(sides).then(modifier))
                }
                extrasAndRelated(detail, actions, sides, returnedFrom, episodeFocus)
            }
        }
    }
}

/** An episode row's menu: Details opens its own page, then Play from beginning and the watched toggle. */
private fun episodeMenu(actions: ItemDetailsActions) = CardMenu(
    onOpen = actions.onOpenItem,
    onPlayFromStart = { actions.onPlay(it, true) },
    onSetWatched = actions.onSetWatched,
)

/**
 * The episodes the page lists: a season's own, or those of the [season] picked in a show; any other item's playable
 * children.
 */
private fun episodesOf(detail: MediaItemDetail, seasons: List<MediaItem>, season: ItemDetailState?): List<MediaItem> {
    val children = when {
        detail.item.kind == ItemKind.SEASON -> detail.children
        seasons.isNotEmpty() -> (season as? ItemDetailState.Ready)?.detail?.children.orEmpty()
        else -> detail.children
    }
    return children.filter { it.kind.playable }
}

/** The item's extras, then the titles like it; the card the viewer [returnedFrom] takes [focus]. */
private fun LazyListScope.extrasAndRelated(
    detail: MediaItemDetail,
    actions: ItemDetailsActions,
    sides: PaddingValues,
    returnedFrom: String?,
    focus: FocusRequester,
) {
    if (detail.extras.isNotEmpty()) {
        item(key = "extras") {
            ItemRow(
                stringResource(Res.string.library_extras),
                detail.extras,
                CardStyle.WIDE,
                sides,
                onOpen = { actions.onPlay(it, false) },
                onPlay = { actions.onPlay(it, false) },
                focusItem = returnedFrom?.takeIf { id -> detail.extras.any { it.id == id } },
                focusRequester = focus,
            )
        }
    }
    if (detail.related.isNotEmpty()) {
        item(key = "related") {
            val style = if (detail.related.all { it.kind == ItemKind.VIDEO }) CardStyle.WIDE else CardStyle.POSTER
            ItemRow(
                stringResource(Res.string.library_related),
                detail.related,
                style,
                sides,
                onOpen = actions.onOpenItem,
                onPlay = { actions.onPlay(it, false) },
                focusItem = returnedFrom?.takeIf { id -> detail.related.any { it.id == id } },
                focusRequester = focus,
            )
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
private fun seasonLabel(season: MediaItem): String = when (val number = season.season) {
    null -> season.title
    0 -> stringResource(Res.string.library_specials)
    else -> stringResource(Res.string.library_season, number)
}

private const val HERO_FRACTION = 0.6f
private const val CAST_SHOWN = 6
