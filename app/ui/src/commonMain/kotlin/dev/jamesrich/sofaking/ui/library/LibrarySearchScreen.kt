package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.SEARCH_DEBOUNCE_MS
import dev.jamesrich.sofaking.data.SearchGroup
import dev.jamesrich.sofaking.data.SearchResults
import dev.jamesrich.sofaking.data.SearchSections
import dev.jamesrich.sofaking.data.searchSections
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_search_albums
import dev.jamesrich.sofaking.resources.library_search_artists
import dev.jamesrich.sofaking.resources.library_search_episodes
import dev.jamesrich.sofaking.resources.library_search_hint
import dev.jamesrich.sofaking.resources.library_search_movies
import dev.jamesrich.sofaking.resources.library_search_none
import dev.jamesrich.sofaking.resources.library_search_people
import dev.jamesrich.sofaking.resources.library_search_people_more
import dev.jamesrich.sofaking.resources.library_search_people_two
import dev.jamesrich.sofaking.resources.library_search_shows
import dev.jamesrich.sofaking.resources.library_search_tracks
import dev.jamesrich.sofaking.resources.library_search_videos
import dev.jamesrich.sofaking.resources.library_search_voice_prompt
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.rememberVoiceSearch
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource

/**
 * Search across every library by title, show or cast: movies, shows and what the query's person is in as posters, then
 * episodes and videos, each in its own row.
 * [voice] starts the platform's speech input on arrival, falling back to the text field without a recognizer;
 * [initialQuery] searches for it on arrival, with focus on the first result; [returnedFrom] is the item whose page
 * the viewer just left, which takes focus.
 */
@Composable
internal fun LibrarySearchScreen(
    actions: LibrarySearchActions,
    voice: Boolean,
    returnedFrom: String?,
    initialQuery: String? = null,
) {
    val insets = screenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues()
    val query = rememberTextFieldState()
    val focus = remember { SearchFocus() }
    val search = remember { SearchProgress() }
    var arrived by rememberSaveable { mutableStateOf(false) }

    val startVoice = rememberVoiceSearch(stringResource(Res.string.library_search_voice_prompt)) { spoken ->
        if (spoken != null) {
            query.setTextAndPlaceCursorAtEnd(spoken)
            search.focusResults = true
        } else {
            search.focusResults = false
            focus.field.requestFocus()
        }
    }
    LaunchedEffect(returnedFrom) {
        if (returnedFrom != null && arrived && query.text.isNotBlank()) search.focusReturn = returnedFrom
    }
    LaunchedEffect(Unit) {
        when {
            arrived && query.text.isNotBlank() -> Unit

            !arrived && initialQuery != null -> {
                query.setTextAndPlaceCursorAtEnd(initialQuery)
                search.focusResults = true
            }

            !arrived && voice && startVoice() -> Unit

            else -> requestFocusWhenAttached(focus.field)
        }
        arrived = true
    }
    RunSearch(query, actions.search, search)
    val sections = remember(search.results) {
        searchSections(search.results.orEmpty(), search.query, search.people)
    }
    FocusAfterSearch(search, sections, focus)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = insets.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "field") { SearchField(query, focus, sections, search, insets) }
        resultRows(sections, focus, insets, actions, returnedFrom)
    }
}

/** A row per group that found something; the first row shown holds the first result. */
private fun LazyListScope.resultRows(
    sections: SearchSections,
    focus: SearchFocus,
    insets: PaddingValues,
    actions: LibrarySearchActions,
    returnedFrom: String?,
) {
    sections.rows.forEachIndexed { index, section ->
        item(key = section.group.name) {
            ResultRow(
                searchGroupTitle(section.group, sections),
                section.items,
                section.group.style,
                insets,
                actions,
                if (index == 0) focus.firstResult else null,
                returnedFrom,
                focus.returnTo,
            )
        }
    }
}

@Composable
private fun searchGroupTitle(group: SearchGroup, sections: SearchSections): String = when (group) {
    SearchGroup.MOVIES -> stringResource(Res.string.library_search_movies)
    SearchGroup.SHOWS -> stringResource(Res.string.library_search_shows)
    SearchGroup.CAST_AND_CREW -> peopleTitle(sections.people, sections.query)
    SearchGroup.EPISODES -> stringResource(Res.string.library_search_episodes)
    SearchGroup.VIDEOS -> stringResource(Res.string.library_search_videos)
    SearchGroup.ARTISTS -> stringResource(Res.string.library_search_artists)
    SearchGroup.ALBUMS -> stringResource(Res.string.library_search_albums)
    SearchGroup.TRACKS -> stringResource(Res.string.library_search_tracks)
}

/** The row of titles found by their cast or directors names those people, up to two, or else the query. */
@Composable
private fun peopleTitle(people: List<String>, query: String): String = when (people.size) {
    0 -> stringResource(Res.string.library_search_people, query)
    1 -> stringResource(Res.string.library_search_people, people[0])
    2 -> stringResource(Res.string.library_search_people_two, people[0], people[1])
    else -> stringResource(Res.string.library_search_people_more, people[0], people[1])
}

/** Where focus goes around a search: the field, the first result, and the result the viewer came back from. */
private class SearchFocus {
    val field = FocusRequester()
    val firstResult = FocusRequester()
    val returnTo = FocusRequester()
}

/**
 * The search's [results] (null before a query), whether one is [searching], and where focus goes once it is in: to the
 * first result after a spoken query ([focusResults]), or back to the result the viewer left ([focusReturn]).
 */
@Stable
private class SearchProgress {
    var results by mutableStateOf<List<MediaItem>?>(null)

    /** The people the server found [results] by, in its order. */
    var people by mutableStateOf(emptyList<String>())

    /** The query [results] answer. */
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var focusResults by mutableStateOf(false)
    var focusReturn by mutableStateOf<String?>(null)
}

/** Runs [search] for the query a moment after the viewer stops typing; a newer query cancels the one running. */
@Composable
private fun RunSearch(query: TextFieldState, search: suspend (String) -> SearchResults, into: SearchProgress) {
    val currentSearch by rememberUpdatedState(search)
    LaunchedEffect(Unit) {
        snapshotFlow { query.text.toString().trim() }.collectLatest { text ->
            if (text.isEmpty()) {
                into.results = null
                return@collectLatest
            }
            delay(SEARCH_DEBOUNCE_MS)
            // A newer query cancels this one mid-request; the loader must still come down.
            into.searching = true
            try {
                val found = currentSearch(text)
                into.query = text
                into.people = found.people
                into.results = found.items
            } finally {
                into.searching = false
            }
        }
    }
}

/**
 * Once a search is in, focus goes back to the result the viewer left, or after a spoken query to the first result;
 * when nothing matched, the field takes it, so the query can be changed.
 */
@Composable
private fun FocusAfterSearch(search: SearchProgress, sections: SearchSections, focus: SearchFocus) {
    LaunchedEffect(sections, search.searching, search.focusReturn) {
        if (search.searching || search.results == null) return@LaunchedEffect
        val target = search.focusReturn
        val onResults = if (sections.found) focus.firstResult else focus.field
        when {
            target != null -> {
                val returned = search.results.orEmpty().any { it.id == target }
                requestFocusWhenAttached(if (returned) focus.returnTo else onResults)
                // Cleared only once the request is done: clearing it restarts this effect, which would cancel the
                // request.
                search.focusReturn = null
            }

            search.focusResults -> {
                search.focusResults = false
                requestFocusWhenAttached(onResults)
            }
        }
    }
}

/** The query field, with a spinner while a search runs or a word when it found nothing. */
@Composable
private fun SearchField(
    query: TextFieldState,
    focus: SearchFocus,
    sections: SearchSections,
    search: SearchProgress,
    insets: PaddingValues,
) {
    Column(
        Modifier.fillMaxWidth().padding(insets.horizontalOnly()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TvTextField(
            state = query,
            focusRequester = focus.field,
            imeAction = ImeAction.Search,
            onAction = {
                if (sections.found) focus.firstResult.requestFocus()
            },
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            hint = stringResource(Res.string.library_search_hint),
        )
        when {
            search.searching -> SofaLoader(size = 32.dp)

            search.results?.isEmpty() == true -> Text(
                stringResource(Res.string.library_search_none),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ResultRow(
    title: String,
    items: List<MediaItem>,
    style: CardStyle,
    insets: PaddingValues,
    actions: LibrarySearchActions,
    firstFocus: FocusRequester?,
    returnedFrom: String?,
    returnFocus: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title, insets)
        // Up and Down come back to the card last focused in the row; focus requested inside it enters through the
        // restorer too, so the card it asks for is its fallback.
        val fallback = when {
            items.any { it.id == returnedFrom } -> returnFocus
            firstFocus != null -> firstFocus
            else -> FocusRequester.Default
        }
        LazyRow(
            Modifier.focusRestorer(fallback),
            contentPadding = insets.horizontalOnly(),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            items(items, key = { it.id }) { item ->
                val modifier = when {
                    item.id == returnedFrom -> Modifier.focusRequester(returnFocus)
                    item === items.first() && firstFocus != null -> Modifier.focusRequester(firstFocus)
                    else -> Modifier
                }
                LibraryCard(item, style, actions.onOpenItem, actions.onPlayItem, actions.menu, modifier)
            }
        }
    }
}
