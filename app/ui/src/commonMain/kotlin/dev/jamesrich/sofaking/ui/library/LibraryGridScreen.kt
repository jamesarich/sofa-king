package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.LibraryGrid
import dev.jamesrich.sofaking.data.LetterJump
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_empty
import dev.jamesrich.sofaking.resources.library_filter_unwatched
import dev.jamesrich.sofaking.resources.library_genre_all
import dev.jamesrich.sofaking.resources.library_items_count
import dev.jamesrich.sofaking.resources.library_letter_jump
import dev.jamesrich.sofaking.resources.library_sort_added
import dev.jamesrich.sofaking.resources.library_sort_rating
import dev.jamesrich.sofaking.resources.library_sort_title
import dev.jamesrich.sofaking.resources.library_sort_watched
import dev.jamesrich.sofaking.resources.library_sort_year
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.retry
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaChip
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One library as a grid: posters for movies and shows, wide cards for videos, under a row of sort chips and an
 * Unwatched filter, a row of genres when the library has any, and in title order a row of first letters when the
 * library is long enough to need one ([showsLetters]). Pages load as the grid nears its end, and a letter loads them
 * through its first item, which takes focus. [returnedFrom] is the item whose page the viewer just left, which takes
 * focus; otherwise the first card does.
 */
@Composable
internal fun LibraryGridScreen(
    library: MediaLibrary?,
    grid: LibraryGrid?,
    actions: LibraryGridActions,
    returnedFrom: String? = null,
) {
    val insets = screenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues()
    val style = if (library?.kind == LibraryKind.VIDEOS) CardStyle.WIDE else CardStyle.POSTER
    val state = rememberLazyGridState()
    val direction = LocalLayoutDirection.current
    val focus = remember { FocusRequester() }
    val focusId = returnedFrom ?: grid?.items?.firstOrNull()?.id
    val hasItems = grid?.items?.isNotEmpty() == true
    // Focus goes to the grid on arrival and back on the card whose page the viewer left; a new sort or filter
    // leaves it on the chip that changed it.
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasItems, returnedFrom) {
        if (hasItems && (!focused || returnedFrom != null)) {
            focused = true
            requestFocusWhenAttached(focus)
        }
    }
    // A letter's first item: the grid scrolls to it once its page is in, and it takes focus.
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    val jumpFocus = remember { FocusRequester() }
    val itemCount = grid?.items?.size ?: 0
    LaunchedEffect(jumpTo, itemCount) {
        val target = jumpTo ?: return@LaunchedEffect
        if (target >= itemCount) return@LaunchedEffect
        // The grid's first item is the header row, so the item's index is one ahead.
        state.scrollToItem(target + 1)
        requestFocusWhenAttached(jumpFocus)
        jumpTo = null
    }
    val onLetter: (LetterJump) -> Unit = { letter ->
        jumpTo = letter.offset
        actions.onLoadThrough(letter.offset)
    }
    val currentGrid by rememberUpdatedState(grid)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            val g = currentGrid ?: return@collect
            // The grid's first item is the header row, so its index is one ahead of the items'.
            if (shouldLoadMore(last - 1, g.items.size, g.next != null) && !g.loading) actions.onLoadMore()
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (style == CardStyle.WIDE) WIDE_WIDTH else POSTER_WIDTH),
        state = state,
        contentPadding = PaddingValues(
            start = insets.calculateStartPadding(direction),
            end = insets.calculateEndPadding(direction),
            top = 8.dp,
            bottom = insets.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            GridHeader(library, grid, actions, onLetter)
        }
        val items = grid?.items.orEmpty()
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val modifier = when {
                index == jumpTo -> Modifier.focusRequester(jumpFocus)
                item.id == focusId -> Modifier.focusRequester(focus)
                else -> Modifier
            }
            LibraryCard(item, style, actions.onOpenItem, actions.onPlayItem, actions.menu, modifier)
        }
        val g = grid
        val unsettled = g != null && (g.loading || g.error != null)
        if (g != null && (unsettled || items.isEmpty())) {
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        g.loading -> SofaLoader(size = 40.dp)

                        g.error != null -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                g.error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SofaButton(onClick = actions.onLoadMore) { Text(stringResource(Res.string.retry)) }
                        }

                        else -> Text(
                            stringResource(Res.string.library_empty),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridHeader(
    library: MediaLibrary?,
    grid: LibraryGrid?,
    actions: LibraryGridActions,
    onLetter: (LetterJump) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(library?.name.orEmpty(), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            grid?.takeIf { !it.loading || it.items.isNotEmpty() }?.let {
                Text(
                    stringResource(Res.string.library_items_count, it.total),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val sort = grid?.sort ?: LibrarySort.TITLE
        // Each chip row comes back to the chip last focused in it, else its selected one.
        val selectedSort = remember { FocusRequester() }
        LazyRow(Modifier.focusRestorer(selectedSort), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(LibrarySort.entries.size) { i ->
                val option = LibrarySort.entries[i]
                val focus = if (option == sort) Modifier.focusRequester(selectedSort) else Modifier
                SofaChip(selected = option == sort, onClick = {
                    actions.onSort(option)
                }, label = stringResource(option.label()), modifier = focus)
            }
            item {
                val unwatched = grid?.unwatched == true
                SofaChip(selected = unwatched, onClick = {
                    actions.onUnwatched(!unwatched)
                }, label = stringResource(Res.string.library_filter_unwatched))
            }
        }
        val genres = grid?.genres.orEmpty()
        if (genres.isNotEmpty()) {
            val genre = grid?.genre
            val selectedGenre = remember { FocusRequester() }
            val genreShown = genre != null && genres.any { it.equals(genre, ignoreCase = true) }
            LazyRow(Modifier.focusRestorer(selectedGenre), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val focus = if (!genreShown) Modifier.focusRequester(selectedGenre) else Modifier
                    SofaChip(selected = genre == null, onClick = {
                        actions.onGenre(null)
                    }, label = stringResource(Res.string.library_genre_all), modifier = focus)
                }
                items(genres, key = { it }) { option ->
                    val selected = option.equals(genre, ignoreCase = true)
                    val focus = if (selected) Modifier.focusRequester(selectedGenre) else Modifier
                    SofaChip(selected = selected, onClick = {
                        actions.onGenre(option)
                    }, label = option, modifier = focus)
                }
            }
        }
        if (grid != null && showsLetters(grid)) {
            // A row like the chips above, so Up from any column of the grid lands in it, on its first letter.
            val firstLetter = remember { FocusRequester() }
            LazyRow(Modifier.focusRestorer(firstLetter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(grid.letters, key = { _, it -> it.letter }) { i, letter ->
                    val description = stringResource(Res.string.library_letter_jump, letter.letter)
                    val focus = if (i == 0) Modifier.focusRequester(firstLetter) else Modifier
                    SofaChip(
                        selected = false,
                        onClick = { onLetter(letter) },
                        label = letter.letter,
                        modifier = focus.semantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

/** A row of letters helps a grid in title order long enough to scroll a while, with enough letters to choose from. */
internal fun showsLetters(grid: LibraryGrid): Boolean =
    grid.sort == LibrarySort.TITLE && grid.total >= LETTERS_MIN_ITEMS && grid.letters.size >= LETTERS_MIN_LETTERS

private const val LETTERS_MIN_ITEMS = 80
private const val LETTERS_MIN_LETTERS = 6

private fun LibrarySort.label(): StringResource = when (this) {
    LibrarySort.TITLE -> Res.string.library_sort_title
    LibrarySort.ADDED -> Res.string.library_sort_added
    LibrarySort.YEAR -> Res.string.library_sort_year
    LibrarySort.RATING -> Res.string.library_sort_rating
    LibrarySort.WATCHED -> Res.string.library_sort_watched
}
