package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import dev.jamesrich.sofaking.data.LiveHit
import dev.jamesrich.sofaking.data.LiveSearchResults
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.SEARCH_DEBOUNCE_MS
import dev.jamesrich.sofaking.data.SearchGroup
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
import dev.jamesrich.sofaking.resources.search_hint
import dev.jamesrich.sofaking.resources.search_on_later
import dev.jamesrich.sofaking.resources.search_on_now
import dev.jamesrich.sofaking.resources.search_voice_prompt
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.rememberVoiceSearch
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource

/**
 * Search across live TV and every library. The channels to watch now come first, found by number, name or what airs
 * on them; then the libraries' titles by title, show or cast: movies, shows and what the query's person is in as
 * posters, then episodes and videos, each in its own row; then the programs coming up in the guide.
 * [voice] starts the platform's speech input on arrival, falling back to the text field without a recognizer;
 * [initialQuery] searches for it on arrival, with focus on the first result; [returnedFrom] is the item whose page
 * the viewer just left, or the channel they were watching, which takes focus.
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

    val prompt = if (actions.live != null) Res.string.search_voice_prompt else Res.string.library_search_voice_prompt
    val startVoice = rememberVoiceSearch(stringResource(prompt)) { spoken ->
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
    RunSearch(query, actions, search)
    val sections = remember(search.results) {
        searchSections(search.results.orEmpty(), search.query, search.people)
    }
    FocusAfterSearch(search, sections, focus)
    val returnKey = search.returnKey(returnedFrom)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = insets.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item(key = "field") { SearchField(query, focus, sections, search, insets, live = actions.live != null) }
            resultRows(sections, search.live, focus, insets, actions, returnKey, onHit = { search.openHit = it })
        }
        OpenHitDetails(actions.live, search)
    }
}

/** The details of the live hit the viewer picked, over the results; closing them gives its card focus back. */
@Composable
private fun OpenHitDetails(live: LiveSearch?, search: SearchProgress) {
    val open = search.openHit ?: return
    live?.details?.invoke(open) {
        search.openHit = null
        search.closedHit = open.key
        search.focusReturn = open.key
    }
}

/**
 * A row per group that found something, between the channels on now and the programs coming up; the first row shown
 * holds the first result.
 */
private fun LazyListScope.resultRows(
    sections: SearchSections,
    live: LiveSearchResults,
    focus: SearchFocus,
    insets: PaddingValues,
    actions: LibrarySearchActions,
    returnKey: String?,
    onHit: (LiveHit) -> Unit,
) {
    var first: FocusRequester? = focus.firstResult
    fun firstFocus(): FocusRequester? = first.also { first = null }
    if (live.now.isNotEmpty()) {
        val rowFocus = LiveRowFocus(firstFocus(), returnKey, focus.returnTo)
        item(key = LIVE_NOW_ROW) {
            LiveSearchRow(stringResource(Res.string.search_on_now), live.now, later = false, insets, rowFocus, onHit)
        }
    }
    for (section in sections.rows) {
        val firstResult = firstFocus()
        item(key = section.group.name) {
            ResultRow(
                searchGroupTitle(section.group, sections),
                section.items,
                section.group.style,
                insets,
                actions,
                firstResult,
                returnKey,
                focus.returnTo,
            )
        }
    }
    if (live.later.isNotEmpty()) {
        val rowFocus = LiveRowFocus(firstFocus(), returnKey, focus.returnTo)
        item(key = LIVE_LATER_ROW) {
            LiveSearchRow(stringResource(Res.string.search_on_later), live.later, later = true, insets, rowFocus, onHit)
        }
    }
}

private const val LIVE_NOW_ROW = "live-now"
private const val LIVE_LATER_ROW = "live-later"

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
 * The search's [results] (null before a query) and what it found in [live] TV, whether one is [searching], and where
 * focus goes once it is in: to the first result after a spoken query ([focusResults]), or back to the result the
 * viewer left ([focusReturn]).
 */
@Stable
private class SearchProgress {
    var results by mutableStateOf<List<MediaItem>?>(null)

    /** The channels and programs the query found in the lineup. */
    var live by mutableStateOf(LiveSearchResults())

    /** The live hit whose details are open over the results. */
    var openHit by mutableStateOf<LiveHit?>(null)

    /** The live hit whose details the viewer closed last, which keeps the way back to its card. */
    var closedHit by mutableStateOf<String?>(null)

    /**
     * The card that takes focus back: the hit whose details just closed, or what the viewer came back from
     * ([returnedFrom]), an item or the channel they watched.
     */
    fun returnKey(returnedFrom: String?): String? = closedHit ?: returnedFrom?.let { live.onChannel(it)?.key ?: it }

    /** Something matched, in the libraries or in live TV. */
    fun found(sections: SearchSections): Boolean = sections.found || live.found

    /** A search is in and nothing matched anywhere. */
    val nothing: Boolean get() = results?.isEmpty() == true && !live.found

    /** The people the server found [results] by, in its order. */
    var people by mutableStateOf(emptyList<String>())

    /** The query [results] answer. */
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var focusResults by mutableStateOf(false)
    var focusReturn by mutableStateOf<String?>(null)
}

/**
 * Runs the search for the query a moment after the viewer stops typing, in the lineup and then the libraries, and
 * shows both answers together; a newer query cancels the one running.
 */
@Composable
private fun RunSearch(query: TextFieldState, actions: LibrarySearchActions, into: SearchProgress) {
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(Unit) {
        snapshotFlow { query.text.toString().trim() }.collectLatest { text ->
            if (text.isEmpty()) {
                into.results = null
                into.live = LiveSearchResults()
                return@collectLatest
            }
            delay(SEARCH_DEBOUNCE_MS)
            // A newer query cancels this one mid-request; the loader must still come down.
            into.searching = true
            try {
                // A day of programs on 200 channels is some ten thousand titles, searched in a few milliseconds.
                val live = currentActions.live?.find(text)
                val found = currentActions.search(text)
                into.query = text
                into.people = found.people
                into.live = live ?: LiveSearchResults()
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
        val onResults = if (search.found(sections)) focus.firstResult else focus.field
        when {
            target != null -> {
                val live = search.live
                val returned = search.results.orEmpty().any { it.id == target } ||
                    (live.now + live.later).any { it.key == target } ||
                    live.onChannel(target) != null
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

/** The query field, with a spinner while a search runs or a word when it found nothing; [live] says it finds TV too. */
@Composable
private fun SearchField(
    query: TextFieldState,
    focus: SearchFocus,
    sections: SearchSections,
    search: SearchProgress,
    insets: PaddingValues,
    live: Boolean,
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
                if (search.found(sections)) focus.firstResult.requestFocus()
            },
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            hint = stringResource(if (live) Res.string.search_hint else Res.string.library_search_hint),
        )
        when {
            search.searching -> SofaLoader(size = 32.dp)

            search.nothing -> Text(
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
