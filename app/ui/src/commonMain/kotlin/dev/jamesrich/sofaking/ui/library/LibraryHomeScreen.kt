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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
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
import dev.jamesrich.sofaking.resources.library_search
import dev.jamesrich.sofaking.resources.library_unavailable
import dev.jamesrich.sofaking.resources.library_unavailable_hint
import dev.jamesrich.sofaking.resources.load_failed_hint
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.unconfigured
import dev.jamesrich.sofaking.resources.unconfigured_hint
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenInsets
import dev.jamesrich.sofaking.ui.theme.SofaActionChip
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The Library tab: Search and a chip for each library, so any library is two presses from the tabs, then Continue
 * watching and each library's recent additions. [returnedFrom] is the item whose page the viewer just left, the
 * [libraryFocusKey] of the library whose grid they left, or [SEARCH_FOCUS_KEY], which takes focus.
 */
@Composable
internal fun LibraryHomeScreen(
    state: LibraryHomeState,
    actions: LibraryHomeActions,
    modifier: Modifier = Modifier,
    returnedFrom: String? = null,
    /** Whether the header above has focus; the shelves scroll back to the top under it. */
    headerFocused: Boolean = false,
) {
    when (state) {
        LibraryHomeState.Loading -> LibrarySkeleton(modifier)

        LibraryHomeState.Unconfigured -> MessageScreen(
            stringResource(Res.string.unconfigured),
            hint = stringResource(Res.string.unconfigured_hint),
        )

        LibraryHomeState.Unavailable -> MessageScreen(
            stringResource(Res.string.library_unavailable),
            hint = stringResource(Res.string.library_unavailable_hint),
        )

        is LibraryHomeState.Failed -> MessageScreen(
            message = stringResource(Res.string.library_load_failed),
            hint = stringResource(Res.string.load_failed_hint),
            detail = state.message,
            onRetry = actions.onRetry,
        )

        is LibraryHomeState.Ready -> {
            val home = state.home
            if (home.shelves.isEmpty() && home.libraries.isEmpty()) {
                MessageScreen(
                    stringResource(Res.string.library_empty),
                    hint = stringResource(Res.string.library_empty_hint),
                )
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
    modifier: Modifier = Modifier,
) {
    val insets = screenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues()
    val returnFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(returnedFrom) {
        if (returnedFrom != null) requestFocusWhenAttached(returnFocus)
    }
    val profile = LocalLayoutProfile.current
    val staysPut = profile.spotlightStaysPut
    // The spotlight follows the card focus last reached on Continue watching or Recently added. It only reads this.
    val focused = remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // Back or Up to the header shows the page from its top, so Down lands on the first row.
    LaunchedEffect(headerFocused) { if (headerFocused) listState.animateScrollToItem(0) }
    // The item returned to may sit in several rows; the first takes the requester.
    val returnShelf = returnedFrom?.let { id -> shelves.firstOrNull { shelf -> shelf.items.any { it.id == id } }?.key }
    Column(modifier.fillMaxSize()) {
        if (staysPut) {
            SpotlightSlot(shelves, {
                focused.value
            }, actions, profile.tv, insets, false, Modifier.weight(SPOTLIGHT_HEIGHT_SHARE))
        }
        val listShare = if (staysPut) 1f - SPOTLIGHT_HEIGHT_SHARE else 1f
        RefreshBox(!profile.tv, actions.onRefresh, shelves, Modifier.weight(listShare)) {
            LazyColumn(
                // The remote's Search key opens the search by voice from anywhere on the page.
                Modifier.fillMaxSize().searchKey { actions.onSearch(true) },
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = insets.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // Without room to stay put, the spotlight is the list's first card and shows the first item.
                if (!staysPut) {
                    item(key = "spotlight") {
                        val card = Modifier.padding(insets.horizontalOnly()).height(COMPACT_SPOTLIGHT_HEIGHT)
                        SpotlightSlot(shelves, {
                            null
                        }, actions, profile.tv, insets, true, card.clip(CardDefaults.shape))
                    }
                }
                item(key = "chips") { LibraryChips(libraries, actions, returnedFrom, returnFocus, searchFocus, insets) }
                items(shelves, key = { it.key }, contentType = { "shelf" }) { shelf ->
                    Shelf(
                        shelf,
                        actions,
                        insets,
                        focusItem = if (shelf.key == returnShelf) {
                            returnedFrom
                        } else {
                            null
                        },
                        focusRequester = returnFocus,
                        onFocus = if (staysPut && feedsSpotlight(shelf)) {
                            { item -> focused.value = item.id }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** Reads the focused item's id here, so a focus move recomposes only the spotlight and not the shelves. */
@Composable
private fun SpotlightSlot(
    shelves: List<MediaShelf>,
    focusedId: () -> String?,
    actions: LibraryHomeActions,
    tv: Boolean,
    insets: PaddingValues,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Spotlight(
        item = spotlightItem(shelves, focusedId()),
        onPlay = actions.onPlayItem.takeUnless { tv },
        insets = insets,
        modifier = modifier,
        compact = compact,
    )
}

/** Pulls the shelves down to read them again where there is no remote; a TV reads them when its tab opens. */
@Composable
private fun RefreshBox(
    enabled: Boolean,
    onRefresh: (() -> Unit)?,
    shelves: List<MediaShelf>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    // New shelves end the indicator; a reload that changes nothing ends it after a moment.
    LaunchedEffect(shelves) { refreshing = false }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(REFRESH_SHOWN_MS)
            refreshing = false
        }
    }
    val pull = enabled && onRefresh != null
    val state = rememberPullToRefreshState()
    Box(
        modifier.pullToRefresh(
            isRefreshing = refreshing,
            state = state,
            enabled = pull,
            onRefresh = {
                refreshing = true
                onRefresh?.invoke()
            },
        ),
    ) {
        content()
        if (pull) {
            PullToRefreshDefaults.Indicator(state, refreshing, Modifier.align(Alignment.TopCenter))
        }
    }
}

/** The remote's Search key runs [onSearch] from anywhere on the page. */
private fun Modifier.searchKey(onSearch: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val search = event.key == Key.Search && event.type == KeyEventType.KeyDown
    if (search) onSearch()
    search
}

private const val REFRESH_SHOWN_MS = 1500L

/** Search, then a chip per library; focus coming back from either lands on its chip ([returnFocus]). */
@Composable
private fun LibraryChips(
    libraries: List<MediaLibrary>,
    actions: LibraryHomeActions,
    returnedFrom: String?,
    returnFocus: FocusRequester,
    searchFocus: FocusRequester,
    insets: PaddingValues,
) {
    // Coming down from the tabs lands on Search, and back from a library or the search on its chip: focus
    // requested inside the row enters through the restorer, so its fallback must be that chip.
    val chipReturn = returnedFrom == SEARCH_FOCUS_KEY || returnedFrom?.startsWith(LIBRARY_FOCUS_PREFIX) == true
    LazyRow(
        modifier = Modifier.focusRestorer(if (chipReturn) returnFocus else searchFocus),
        contentPadding = insets.horizontalOnly(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = SEARCH_FOCUS_KEY) {
            val focus = (
                if (returnedFrom == SEARCH_FOCUS_KEY) {
                    Modifier.focusRequester(returnFocus)
                } else {
                    Modifier
                }
                ).focusRequester(searchFocus)
            SofaActionChip(onClick = {
                actions.onSearch(false)
            }, label = stringResource(Res.string.library_search), modifier = focus)
        }
        items(libraries, key = { it.id }) { library ->
            val focus = if (returnedFrom == libraryFocusKey(library.id)) {
                Modifier.focusRequester(returnFocus)
            } else {
                Modifier
            }
            SofaActionChip(
                onClick = { actions.onOpenLibrary(library) },
                label = stringResource(Res.string.library_library_chip, library.name, library.itemCount),
                modifier = focus,
            )
        }
    }
}

@Composable
private fun Shelf(
    shelf: MediaShelf,
    actions: LibraryHomeActions,
    insets: PaddingValues,
    focusItem: String?,
    focusRequester: FocusRequester,
    /** Called with the card that takes focus, on the shelves that feed the spotlight. */
    onFocus: ((MediaItem) -> Unit)? = null,
) {
    val style = cardStyleFor(shelf)
    val menu = actions.menuFor(shelf)
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
                val returning = if (item.id == focusItem) Modifier.focusRequester(focusRequester) else Modifier
                val tracking = if (onFocus != null) {
                    Modifier.onFocusChanged { if (it.hasFocus) onFocus(item) }
                } else {
                    Modifier
                }
                LibraryCard(
                    item,
                    style,
                    actions.onOpenItem,
                    actions.onPlayItem,
                    menu,
                    returning.then(tracking),
                    okPlays = cardOkFor(shelf, item) == CardOk.PLAY,
                )
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
