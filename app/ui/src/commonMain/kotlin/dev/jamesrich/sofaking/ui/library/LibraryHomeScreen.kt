package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.LibraryHomeState
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource

/** What the library home's cards do. OK opens an item's page; the remote's Play key plays an item that plays. */
internal class LibraryHomeActions(
    val onOpenItem: (MediaItem) -> Unit,
    val onPlayItem: (MediaItem) -> Unit,
    val onOpenLibrary: (MediaLibrary) -> Unit,
    val onRetry: () -> Unit,
    /** What a held OK on a card offers. */
    val menu: CardMenu? = null,
)

/**
 * The Library tab: Continue watching, each library's recent additions, then the libraries themselves, so any
 * library is two presses from the tabs. [returnedFrom] is the item whose page the viewer just left, or the
 * [libraryFocusKey] of the library whose grid they left, which takes focus.
 */
@Composable
internal fun LibraryHomeScreen(
    state: LibraryHomeState,
    actions: LibraryHomeActions,
    returnedFrom: String? = null,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LibraryHomeState.Loading -> MessageScreen(stringResource(Res.string.library_loading), loading = true)
        LibraryHomeState.Unconfigured -> MessageScreen(stringResource(Res.string.unconfigured), hint = stringResource(Res.string.unconfigured_hint))
        LibraryHomeState.Unavailable -> MessageScreen(stringResource(Res.string.library_unavailable), hint = stringResource(Res.string.library_unavailable_hint))
        is LibraryHomeState.Failed -> MessageScreen(
            message = stringResource(Res.string.library_load_failed),
            detail = state.message,
            onRetry = actions.onRetry,
        )
        is LibraryHomeState.Ready -> {
            val home = state.home
            if (home.shelves.isEmpty() && home.libraries.isEmpty()) {
                MessageScreen(stringResource(Res.string.library_empty), hint = stringResource(Res.string.library_empty_hint))
            } else {
                Shelves(home.shelves, home.libraries, actions, returnedFrom, modifier)
            }
        }
    }
}

@Composable
private fun Shelves(shelves: List<MediaShelf>, libraries: List<MediaLibrary>, actions: LibraryHomeActions, returnedFrom: String?, modifier: Modifier) {
    val insets = screenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues()
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(returnedFrom) {
        if (returnedFrom != null) requestFocusWhenAttached(returnFocus)
    }
    // The item returned to may sit in several rows; the first takes the requester.
    val returnShelf = returnedFrom?.let { id -> shelves.firstOrNull { shelf -> shelf.items.any { it.id == id } }?.id }
    LazyColumn(
        modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(top = 8.dp, bottom = insets.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        items(shelves, key = { it.id }) { shelf ->
            Shelf(shelf, actions, insets, focusItem = if (shelf.id == returnShelf) returnedFrom else null, focusRequester = returnFocus)
        }
        if (libraries.isNotEmpty()) {
            item(key = "libraries") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(stringResource(Res.string.library_libraries), insets)
                    LazyRow(
                        contentPadding = insets.horizontalOnly(),
                        horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                    ) {
                        items(libraries, key = { it.id }) { library ->
                            val focus = if (returnedFrom == libraryFocusKey(library.id)) Modifier.focusRequester(returnFocus) else Modifier
                            LibraryTile(library, focus) { actions.onOpenLibrary(library) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelf(shelf: MediaShelf, actions: LibraryHomeActions, insets: PaddingValues, focusItem: String?, focusRequester: FocusRequester) {
    val style = cardStyleFor(shelf)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(shelf.title, insets)
        LazyRow(
            contentPadding = insets.horizontalOnly(),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            items(shelf.items, key = { it.id }) { item ->
                val focus = if (item.id == focusItem) Modifier.focusRequester(focusRequester) else Modifier
                val play = if (item.kind.playable) ({ actions.onPlayItem(item) }) else null
                when (style) {
                    CardStyle.POSTER -> PosterCard(item, onClick = { actions.onOpenItem(item) }, onPlay = play, modifier = focus, menu = actions.menu)
                    CardStyle.WIDE -> WideCard(item, onClick = { actions.onOpenItem(item) }, onPlay = play, modifier = focus, menu = actions.menu)
                }
            }
        }
    }
}

/** A library as a wide tile with its name and how much it holds. */
@Composable
private fun LibraryTile(library: MediaLibrary, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (library.kind == LibraryKind.SHOWS) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = modifier.width(WIDE_WIDTH).aspectRatio(16f / 9f).tvFocus(CardShape, 1.05f),
    ) {
        Box(Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                library.name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                stringResource(Res.string.library_items_count, library.itemCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/** What the home's `returnedFrom` holds for a library's tile, beside the items' own ids. */
internal fun libraryFocusKey(libraryId: String): String = "library:$libraryId"

/** A row's title, inside the screen's side margins. */
@Composable
internal fun SectionTitle(text: String, insets: PaddingValues) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(insets.horizontalOnly()),
    )
}

/** The side padding of [this], for a row that scrolls to the screen's edges but starts and ends inside its margins. */
@Composable
internal fun PaddingValues.horizontalOnly(): PaddingValues {
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    return PaddingValues(start = calculateStartPadding(direction), end = calculateEndPadding(direction))
}
