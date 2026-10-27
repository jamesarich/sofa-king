package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.LibraryGrid
import dev.jamesrich.sofaking.data.LetterJump
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_empty
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.retry
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import dev.jamesrich.sofaking.ui.theme.SofaButton
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
    val style = gridStyleFor(library?.kind)
    val state = rememberLazyGridState()
    val direction = LocalLayoutDirection.current
    val focus = remember { FocusRequester() }
    val focusId = returnedFrom ?: grid?.items?.firstOrNull()?.id
    FocusOnArrival(grid?.items?.isNotEmpty() == true, returnedFrom, focus)
    val jump = rememberGridJump(state, grid?.items?.size ?: 0)
    val onLetter: (LetterJump) -> Unit = { letter ->
        jump.target = letter.offset
        actions.onLoadThrough(letter.offset)
    }
    // The row of letters, when the grid has one: Up from the grid's top row lands in it from every column.
    val letters = remember { FocusRequester() }
    val letterRow = letters.takeIf { grid != null && showsLetters(grid) }
    LoadMoreNearEnd(state, grid, actions.onLoadMore)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(
            when (style) {
                CardStyle.WIDE -> WIDE_WIDTH
                CardStyle.SQUARE -> SQUARE_WIDTH
                CardStyle.POSTER -> POSTER_WIDTH
            },
        ),
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
            GridHeader(library, grid, actions, onLetter, letterRow)
        }
        val items = grid?.items.orEmpty()
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val modifier = when {
                index == jump.target -> Modifier.focusRequester(jump.focus)
                item.id == focusId -> Modifier.focusRequester(focus)
                else -> Modifier
            }
            val up = if (letterRow == null) Modifier else Modifier.upToLetters(state, index, letterRow)
            LibraryCard(item, style, actions.onOpenItem, actions.onPlayItem, actions.menu, up.then(modifier))
        }
        val g = grid
        val unsettled = g != null && (g.loading || g.error != null)
        if (g != null && (unsettled || items.isEmpty())) {
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { GridFooter(g, actions.onLoadMore) }
        }
    }
}

/**
 * Focus goes to [focus] when the grid first has items and again when the viewer comes back from a card's page
 * ([returnedFrom]); a new sort or filter leaves it on the chip that changed it.
 */
@Composable
private fun FocusOnArrival(hasItems: Boolean, returnedFrom: String?, focus: FocusRequester) {
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasItems, returnedFrom) {
        if (hasItems && (!focused || returnedFrom != null)) {
            focused = true
            requestFocusWhenAttached(focus)
        }
    }
}

/** A letter's first item, the [target]: the grid scrolls to it once its page is in, and it takes [focus]. */
@Stable
private class GridJump {
    var target by mutableStateOf<Int?>(null)
    val focus = FocusRequester()
}

@Composable
private fun rememberGridJump(state: LazyGridState, itemCount: Int): GridJump {
    val jump = remember { GridJump() }
    LaunchedEffect(jump.target, itemCount) {
        val target = jump.target ?: return@LaunchedEffect
        if (target >= itemCount) return@LaunchedEffect
        // The grid's first item is the header row, so the item's index is one ahead.
        state.scrollToItem(target + 1)
        requestFocusWhenAttached(jump.focus)
        jump.target = null
    }
    return jump
}

/**
 * Up from the card at [index] goes to [letterRow] from the grid's top row of cards while the header is on screen. The
 * header is the grid's row 0, so that top row is row 1.
 */
private fun Modifier.upToLetters(state: LazyGridState, index: Int, letterRow: FocusRequester): Modifier =
    focusProperties {
        val visible = state.layoutInfo.visibleItemsInfo
        val row = visible.firstOrNull { it.index == index + 1 }?.row
        if (row == 1 && visible.any { it.index == 0 }) up = letterRow
    }

/** The grid's last row: a spinner while a page loads, the failure with Retry, or a word that the library is empty. */
@Composable
private fun GridFooter(grid: LibraryGrid, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        when {
            grid.loading -> SofaLoader(size = 40.dp)

            grid.error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    grid.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SofaButton(main = true, onClick = onRetry) { Text(stringResource(Res.string.retry)) }
            }

            else -> Text(
                stringResource(Res.string.library_empty),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Asks for the next page once the grid scrolls near the end of what [grid] holds and none is loading. */
@Composable
private fun LoadMoreNearEnd(state: LazyGridState, grid: LibraryGrid?, onLoadMore: () -> Unit) {
    val currentGrid by rememberUpdatedState(grid)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            val g = currentGrid ?: return@collect
            // The grid's first item is the header row, so its index is one ahead of the items'.
            if (shouldLoadMore(last - 1, g.items.size, g.next != null) && !g.loading) currentOnLoadMore()
        }
    }
}

/** A row of letters helps a grid in title order long enough to scroll a while, with enough letters to choose from. */
internal fun showsLetters(grid: LibraryGrid): Boolean =
    grid.sort == LibrarySort.TITLE && grid.total >= LETTERS_MIN_ITEMS && grid.letters.size >= LETTERS_MIN_LETTERS

private const val LETTERS_MIN_ITEMS = 80
private const val LETTERS_MIN_LETTERS = 6
