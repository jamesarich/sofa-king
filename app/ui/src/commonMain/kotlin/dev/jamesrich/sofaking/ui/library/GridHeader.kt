package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.LibraryGrid
import dev.jamesrich.sofaking.data.LetterJump
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.data.MusicView
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_collection_all
import dev.jamesrich.sofaking.resources.library_filter_unwatched
import dev.jamesrich.sofaking.resources.library_genre_all
import dev.jamesrich.sofaking.resources.library_items_count
import dev.jamesrich.sofaking.resources.library_letter_jump
import dev.jamesrich.sofaking.resources.library_sort_added
import dev.jamesrich.sofaking.resources.library_sort_by
import dev.jamesrich.sofaking.resources.library_sort_played
import dev.jamesrich.sofaking.resources.library_sort_plays
import dev.jamesrich.sofaking.resources.library_sort_rating
import dev.jamesrich.sofaking.resources.library_sort_title
import dev.jamesrich.sofaking.resources.library_sort_watched
import dev.jamesrich.sofaking.resources.library_sort_year
import dev.jamesrich.sofaking.resources.library_view_albums
import dev.jamesrich.sofaking.resources.library_view_artists
import dev.jamesrich.sofaking.resources.library_view_favorites
import dev.jamesrich.sofaking.resources.library_view_songs
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.ui.sectionHeading
import dev.jamesrich.sofaking.ui.theme.SofaActionChip
import dev.jamesrich.sofaking.ui.theme.SofaChip
import dev.jamesrich.sofaking.ui.theme.SofaMenuChip
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GridHeader(
    library: MediaLibrary?,
    grid: LibraryGrid?,
    actions: LibraryGridActions,
    onLetter: (LetterJump) -> Unit,
    letterRow: FocusRequester?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                library?.name.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                modifier = Modifier.sectionHeading(),
            )
            grid?.takeIf { !it.loading || it.items.isNotEmpty() }?.let {
                Text(
                    pluralStringResource(Res.plurals.library_items_count, it.total, it.total),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (library?.kind == LibraryKind.MUSIC) ViewChips(grid, actions)
        FilterBar(library?.kind, grid, actions, letterRow)
        if (letterRow != null) LetterChips(grid, letterRow, onLetter)
    }
}

/** A Music library's artists, albums or songs; each view keeps the sort and genre it is shown in. */
@Composable
private fun ViewChips(grid: LibraryGrid?, actions: LibraryGridActions) {
    val view = grid?.view ?: MusicView.ARTISTS
    val selectedView = remember { FocusRequester() }
    LazyRow(Modifier.focusRestorer(selectedView), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(MusicView.entries, key = { it.name }) { option ->
            val focus = if (option == view) Modifier.focusRequester(selectedView) else Modifier
            SofaChip(selected = option == view, onClick = {
                actions.onView(option)
            }, label = stringResource(option.label()), modifier = focus)
        }
    }
}

/**
 * The whole filter bar in one row: the sort, the genre and, in a library with any, the collection as menus, then the
 * Unwatched toggle where the library has it. Down from any of them goes to the letters, which the grid's search would
 * pass over from the row's left end.
 */
@Composable
private fun FilterBar(kind: LibraryKind?, grid: LibraryGrid?, actions: LibraryGridActions, letterRow: FocusRequester?) {
    val sort = grid?.sort ?: LibrarySort.TITLE
    val sorts = gridSorts(kind, grid?.view ?: MusicView.ARTISTS)
    val genres = grid?.genres.orEmpty()
    val genre = grid?.genre?.let { current -> genres.firstOrNull { it.equals(current, ignoreCase = true) } }
    val collections = grid?.collections.orEmpty()
    val collection = grid?.collection?.let { open -> collections.firstOrNull { it.equals(open, ignoreCase = true) } }
    val firstChip = remember { FocusRequester() }
    val toLetters = Modifier.focusProperties { if (letterRow != null) down = letterRow }
    FlowRow(
        Modifier.focusRestorer(firstChip),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SofaMenuChip(
            label = stringResource(Res.string.library_sort_by, stringResource(sort.label())),
            options = sorts,
            selected = sort,
            optionLabel = { stringResource(it.label()) },
            onSelect = actions.onSort,
            modifier = toLetters,
            focusRequester = firstChip,
        )
        if (genres.isNotEmpty()) {
            SofaMenuChip(
                label = genre ?: stringResource(Res.string.library_genre_all),
                options = listOf<String?>(null) + genres,
                selected = genre,
                optionLabel = { it ?: stringResource(Res.string.library_genre_all) },
                onSelect = actions.onGenre,
                modifier = toLetters,
            )
        }
        if (collections.isNotEmpty()) {
            SofaMenuChip(
                label = collection ?: stringResource(Res.string.library_collection_all),
                options = listOf<String?>(null) + collections,
                selected = collection,
                optionLabel = { it ?: stringResource(Res.string.library_collection_all) },
                onSelect = actions.onCollection,
                modifier = toLetters,
            )
        }
        if (gridFiltersUnwatched(kind)) {
            val unwatched = grid?.unwatched == true
            SofaChip(
                selected = unwatched,
                onClick = { actions.onUnwatched(!unwatched) },
                label = stringResource(Res.string.library_filter_unwatched),
                modifier = toLetters,
            )
        }
    }
}

/**
 * The first letters, each loading the grid through its first item. The row comes back to the letter last focused,
 * else the first; only focus scrolls the row, so whichever it is is on screen.
 */
@Composable
private fun LetterChips(grid: LibraryGrid?, letterRow: FocusRequester, onLetter: (LetterJump) -> Unit) {
    val firstLetter = remember { FocusRequester() }
    LazyRow(
        Modifier.focusRequester(letterRow).focusRestorer(firstLetter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(grid?.letters.orEmpty(), key = { _, jump -> jump.letter }) { i, letter ->
            val description = stringResource(Res.string.library_letter_jump, letter.letter)
            val focus = if (i == 0) Modifier.focusRequester(firstLetter) else Modifier
            SofaActionChip(
                onClick = { onLetter(letter) },
                label = letter.letter,
                modifier = focus.semantics { contentDescription = description },
            )
        }
    }
}

private fun MusicView.label(): StringResource = when (this) {
    MusicView.ARTISTS -> Res.string.library_view_artists
    MusicView.ALBUMS -> Res.string.library_view_albums
    MusicView.SONGS -> Res.string.library_view_songs
    MusicView.FAVORITES -> Res.string.library_view_favorites
}

private fun LibrarySort.label(): StringResource = when (this) {
    LibrarySort.TITLE -> Res.string.library_sort_title
    LibrarySort.ADDED -> Res.string.library_sort_added
    LibrarySort.YEAR -> Res.string.library_sort_year
    LibrarySort.RATING -> Res.string.library_sort_rating
    LibrarySort.WATCHED -> Res.string.library_sort_watched
    LibrarySort.PLAYED -> Res.string.library_sort_played
    LibrarySort.PLAYS -> Res.string.library_sort_plays
}
