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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.rememberVoiceSearch
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource

/** What the search's cards do, as on the library's other pages. */
internal class LibrarySearchActions(
    val search: suspend (String) -> List<MediaItem>,
    val onOpenItem: (MediaItem) -> Unit,
    val onPlayItem: (MediaItem) -> Unit,
    val menu: CardMenu? = null,
)

/**
 * Search across every library by title, show or cast: movies and shows as posters, then episodes and videos.
 * [voice] starts the platform's speech input on arrival, falling back to the text field without a recognizer;
 * [initialQuery] searches for it on arrival, with focus on the first result; [returnedFrom] is the item whose page
 * the viewer just left, which takes focus.
 */
@Composable
internal fun LibrarySearchScreen(actions: LibrarySearchActions, voice: Boolean, returnedFrom: String?, initialQuery: String? = null) {
    val insets = screenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues()
    val query = rememberTextFieldState()
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    var results by remember { mutableStateOf<List<MediaItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    // A spoken query moves focus to the first result once it is in; a typed one leaves it in the field.
    var focusResults by remember { mutableStateOf(false) }
    // Back from a result's page: the results are searched again, then the result left takes focus.
    var focusReturn by remember { mutableStateOf(returnedFrom != null) }
    var arrived by rememberSaveable { mutableStateOf(false) }
    val currentSearch by rememberUpdatedState(actions.search)

    val startVoice = rememberVoiceSearch(stringResource(Res.string.library_search_voice_prompt)) { spoken ->
        if (spoken != null) {
            query.setTextAndPlaceCursorAtEnd(spoken)
            focusResults = true
        } else {
            focusResults = false
            field.requestFocus()
        }
    }
    LaunchedEffect(Unit) {
        if (query.text.isBlank()) focusReturn = false
        when {
            focusReturn -> Unit
            !arrived && initialQuery != null -> {
                query.setTextAndPlaceCursorAtEnd(initialQuery)
                focusResults = true
            }
            !arrived && voice && startVoice() -> Unit
            else -> requestFocusWhenAttached(field)
        }
        arrived = true
    }
    LaunchedEffect(Unit) {
        snapshotFlow { query.text.toString().trim() }.collectLatest { text ->
            if (text.isEmpty()) {
                results = null
                return@collectLatest
            }
            delay(SEARCH_DEBOUNCE_MS)
            searching = true
            results = currentSearch(text)
            searching = false
        }
    }
    val sections = remember(results) { searchSections(results.orEmpty()) }
    LaunchedEffect(sections, searching) {
        if (focusReturn && !searching && results != null) {
            focusReturn = false
            requestFocusWhenAttached(if (results.orEmpty().any { it.id == returnedFrom }) returnFocus else firstResult)
            return@LaunchedEffect
        }
        if (!focusResults || searching || results == null) return@LaunchedEffect
        focusResults = false
        // Nothing matched what was said: the field takes focus so the query can be changed.
        requestFocusWhenAttached(if (sections.posters.isNotEmpty() || sections.wide.isNotEmpty()) firstResult else field)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = insets.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "field") {
            Column(Modifier.fillMaxWidth().padding(insets.horizontalOnly()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(
                    state = query,
                    focusRequester = field,
                    imeAction = ImeAction.Search,
                    onAction = { if (sections.posters.isNotEmpty() || sections.wide.isNotEmpty()) firstResult.requestFocus() },
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    hint = stringResource(Res.string.library_search_hint),
                )
                when {
                    searching -> SofaLoader(size = 32.dp)
                    results?.isEmpty() == true -> Text(stringResource(Res.string.library_search_none), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (sections.posters.isNotEmpty()) {
            item(key = "posters") {
                ResultRow(stringResource(Res.string.library_search_titles), sections.posters, CardStyle.POSTER, insets, actions, firstResult, returnedFrom, returnFocus)
            }
        }
        if (sections.wide.isNotEmpty()) {
            item(key = "wide") {
                val first = if (sections.posters.isEmpty()) firstResult else null
                ResultRow(stringResource(Res.string.library_search_episodes), sections.wide, CardStyle.WIDE, insets, actions, first, returnedFrom, returnFocus)
            }
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
        LazyRow(contentPadding = insets.horizontalOnly(), horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
            items(items, key = { it.id }) { item ->
                val modifier = when {
                    item.id == returnedFrom -> Modifier.focusRequester(returnFocus)
                    item === items.first() && firstFocus != null -> Modifier.focusRequester(firstFocus)
                    else -> Modifier
                }
                val play = if (item.kind.playable) ({ actions.onPlayItem(item) }) else null
                when (style) {
                    CardStyle.POSTER -> PosterCard(item, onClick = { actions.onOpenItem(item) }, onPlay = play, modifier = modifier, menu = actions.menu)
                    CardStyle.WIDE -> WideCard(item, onClick = { actions.onOpenItem(item) }, onPlay = play, modifier = modifier, menu = actions.menu)
                }
            }
        }
    }
}

/** Long enough that typing a word on a remote's keyboard asks the server once, not per letter. */
private const val SEARCH_DEBOUNCE_MS = 400L
