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
import dev.jamesrich.sofaking.data.billed
import dev.jamesrich.sofaking.data.hidesSpoilers
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_albums
import dev.jamesrich.sofaking.resources.library_extras
import dev.jamesrich.sofaking.resources.library_load_failed
import dev.jamesrich.sofaking.resources.library_loading
import dev.jamesrich.sofaking.resources.library_popular
import dev.jamesrich.sofaking.resources.library_related
import dev.jamesrich.sofaking.resources.library_season
import dev.jamesrich.sofaking.resources.library_season_failed
import dev.jamesrich.sofaking.resources.library_specials
import dev.jamesrich.sofaking.resources.load_failed_hint
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.retry
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.ScrollOnlyWhenHidden
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import dev.jamesrich.sofaking.ui.sectionHeading
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaChip
import org.jetbrains.compose.resources.stringResource

/**
 * An artist's most played songs, the item's extras, the rest of a movie's collection, then the titles like it
 * ([collectionAndRelated]); the song or card the viewer [returnedFrom] takes [focus].
 */
internal fun LazyListScope.extrasAndRelated(
    detail: MediaItemDetail,
    actions: ItemDetailsActions,
    sides: PaddingValues,
    returnedFrom: String?,
    focus: FocusRequester,
) {
    if (detail.popular.isNotEmpty()) {
        item(key = "popular") {
            Text(
                stringResource(Res.string.library_popular),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(sides).sectionHeading(),
            )
        }
        itemsIndexed(detail.popular, key = { _, song -> "popular-${song.id}" }) { index, song ->
            val back = if (song.id == returnedFrom) Modifier.focusRequester(focus) else Modifier
            TrackRow(
                song,
                detail.item,
                discs = false,
                onClick = { actions.onPlay(song, true) },
                modifier = Modifier.padding(sides).then(back),
                number = "${index + 1}",
                under = song.album,
            )
        }
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
                focusItem = returnedFrom?.takeIf { id -> detail.extras.any { it.id == id } },
                focusRequester = focus,
            )
        }
    }
    collectionAndRelated(detail, actions, sides, returnedFrom, focus)
}

/** The rest of a movie's collection, then the titles like it; the card the viewer [returnedFrom] takes [focus]. */
private fun LazyListScope.collectionAndRelated(
    detail: MediaItemDetail,
    actions: ItemDetailsActions,
    sides: PaddingValues,
    returnedFrom: String?,
    focus: FocusRequester,
) {
    val collection = collectionRow(detail)
    if (collection.isNotEmpty()) {
        item(key = "collection") {
            ItemRow(
                detail.collection?.name.orEmpty(),
                collection,
                CardStyle.POSTER,
                sides,
                onOpen = actions.onOpenItem,
                onPlay = { actions.onPlay(it, false) },
                focusItem = returnedFrom?.takeIf { id -> collection.any { it.id == id } },
                focusRequester = focus,
            )
        }
    }
    if (detail.related.isNotEmpty()) {
        item(key = "related") {
            val style = when {
                detail.related.all { it.kind == ItemKind.VIDEO } -> CardStyle.WIDE
                detail.related.all { it.kind == ItemKind.ARTIST } -> CardStyle.SQUARE
                else -> CardStyle.POSTER
            }
            ItemRow(
                stringResource(Res.string.library_related),
                detail.related,
                style,
                sides,
                onOpen = actions.onOpenItem,
                onPlay = { actions.onPlay(it, false) },
                // One in the collection's row too takes focus there, the row above.
                focusItem = returnedFrom?.takeIf { id ->
                    detail.related.any { it.id == id } && collection.none { it.id == id }
                },
                focusRequester = focus,
            )
        }
    }
}

/** Everything in the rows under an item's summary that a viewer can come back from, but its extras. */
internal fun rowsUnder(detail: MediaItemDetail): List<MediaItem> =
    detail.related + detail.popular + collectionRow(detail)

/** The other movies of the item's collection, in release order, which its page lists under the collection's name. */
internal fun collectionRow(detail: MediaItemDetail): List<MediaItem> =
    detail.collection?.items.orEmpty().filter { it.id != detail.item.id }

/**
 * A titled row of cards: OK on one calls [onOpen], the remote's Play key [onPlay]; [focusItem] takes [focusRequester].
 */
@Composable
internal fun ItemRow(
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
