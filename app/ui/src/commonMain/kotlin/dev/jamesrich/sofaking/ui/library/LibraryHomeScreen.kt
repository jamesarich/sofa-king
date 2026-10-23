package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.ui.theme.SofaChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.LibraryHomeState
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_empty
import dev.jamesrich.sofaking.resources.library_empty_hint
import dev.jamesrich.sofaking.resources.library_library_chip
import dev.jamesrich.sofaking.resources.library_load_failed
import dev.jamesrich.sofaking.resources.library_loading
import dev.jamesrich.sofaking.resources.library_search
import dev.jamesrich.sofaking.resources.library_unavailable
import dev.jamesrich.sofaking.resources.library_unavailable_hint
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.unconfigured
import dev.jamesrich.sofaking.resources.unconfigured_hint
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import org.jetbrains.compose.resources.stringResource

/** What the library home's cards do. OK opens an item's page; the remote's Play key plays an item that plays. */
internal class LibraryHomeActions(
    val onOpenItem: (MediaItem) -> Unit,
    val onPlayItem: (MediaItem) -> Unit,
    val onOpenLibrary: (MediaLibrary) -> Unit,
    val onRetry: () -> Unit,
    /** Opens the search; true starts it by voice. */
    val onSearch: (voice: Boolean) -> Unit = {},
    /** What a held OK on a card offers. */
    val menu: CardMenu? = null,
)

/**
 * The Library tab: Search and a chip for each library, so any library is two presses from the tabs, then Continue
 * watching and each library's recent additions. [returnedFrom] is the item whose page the viewer just left, the
 * [libraryFocusKey] of the library whose grid they left, or [SEARCH_FOCUS_KEY], which takes focus.
 */
@Composable
internal fun LibraryHomeScreen(
    state: LibraryHomeState,
    actions: LibraryHomeActions,
    returnedFrom: String? = null,
    /** Whether the header above has focus; the shelves scroll back to the top under it. */
    headerFocused: Boolean = false,
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
                Shelves(home.shelves, home.libraries, actions, returnedFrom, headerFocused, modifier)
            }
        }
    }
}

@Composable
private fun Shelves(
    shelves: List<MediaShelf>,
    libraries: List<MediaLibrary>,
    actions: LibraryHomeActions,
    returnedFrom: String?,
    headerFocused: Boolean,
    modifier: Modifier,
) {
    val insets = screenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues()
    val returnFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(returnedFrom) {
        if (returnedFrom != null) requestFocusWhenAttached(returnFocus)
    }
    val listState = rememberLazyListState()
    // Back or Up to the header shows the page from its top, so Down lands on the first row.
    LaunchedEffect(headerFocused) { if (headerFocused) listState.animateScrollToItem(0) }
    // The item returned to may sit in several rows; the first takes the requester.
    val returnShelf = returnedFrom?.let { id -> shelves.firstOrNull { shelf -> shelf.items.any { it.id == id } }?.key }
    LazyColumn(
        // The remote's Search key opens the search by voice from anywhere on the page.
        modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.key == Key.Search && event.type == KeyEventType.KeyDown) {
                actions.onSearch(true)
                true
            } else {
                false
            }
        },
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = insets.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "chips") {
            // Coming down from the tabs lands on Search, and back from a library or the search on its chip: focus
            // requested inside the row enters through the restorer, so its fallback must be that chip.
            val chipReturn = returnedFrom == SEARCH_FOCUS_KEY || returnedFrom?.startsWith(LIBRARY_FOCUS_PREFIX) == true
            LazyRow(
                modifier = Modifier.focusRestorer(if (chipReturn) returnFocus else searchFocus),
                contentPadding = insets.horizontalOnly(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = SEARCH_FOCUS_KEY) {
                    val focus = (if (returnedFrom == SEARCH_FOCUS_KEY) Modifier.focusRequester(returnFocus) else Modifier).focusRequester(searchFocus)
                    SofaChip(selected = false, onClick = { actions.onSearch(false) }, label = stringResource(Res.string.library_search), modifier = focus)
                }
                items(libraries, key = { it.id }) { library ->
                    val focus = if (returnedFrom == libraryFocusKey(library.id)) Modifier.focusRequester(returnFocus) else Modifier
                    SofaChip(
                        selected = false,
                        onClick = { actions.onOpenLibrary(library) },
                        label = stringResource(Res.string.library_library_chip, library.name, library.itemCount),
                        modifier = focus,
                    )
                }
            }
        }
        items(shelves, key = { it.key }) { shelf ->
            Shelf(shelf, actions, insets, focusItem = if (shelf.key == returnShelf) returnedFrom else null, focusRequester = returnFocus)
        }
    }
}

@Composable
private fun Shelf(shelf: MediaShelf, actions: LibraryHomeActions, insets: PaddingValues, focusItem: String?, focusRequester: FocusRequester) {
    val style = cardStyleFor(shelf)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(shelf.title, insets)
        // Up and Down come back to the card last focused in the shelf; focus requested inside it enters through
        // the restorer too, so the card returned to is its fallback.
        LazyRow(
            modifier = Modifier.focusRestorer(if (focusItem != null) focusRequester else FocusRequester.Default),
            contentPadding = insets.horizontalOnly(),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            items(shelf.items, key = { it.id }) { item ->
                val focus = if (item.id == focusItem) Modifier.focusRequester(focusRequester) else Modifier
                LibraryCard(item, style, actions.onOpenItem, actions.onPlayItem, actions.menu, focus)
            }
        }
    }
}

/** What the home's `returnedFrom` holds for a library's chip, beside the items' own ids. */
internal fun libraryFocusKey(libraryId: String): String = LIBRARY_FOCUS_PREFIX + libraryId

private const val LIBRARY_FOCUS_PREFIX = "library:"

/** What the home's `returnedFrom` holds for the Search chip. */
internal const val SEARCH_FOCUS_KEY = "search"

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
