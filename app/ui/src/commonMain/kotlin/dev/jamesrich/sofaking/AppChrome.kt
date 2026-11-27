package dev.jamesrich.sofaking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.app_name
import dev.jamesrich.sofaking.resources.nav_dvr
import dev.jamesrich.sofaking.resources.nav_library
import dev.jamesrich.sofaking.resources.nav_live
import dev.jamesrich.sofaking.resources.nav_search
import dev.jamesrich.sofaking.resources.nav_settings
import dev.jamesrich.sofaking.resources.refreshing
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.ui.KidsBadge
import dev.jamesrich.sofaking.ui.LocalKidsMode
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.LogoMark
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.SofaLoaderLabel
import dev.jamesrich.sofaking.ui.announcesChanges
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaIconAction
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.Tab as MaterialTab

/**
 * A page under the tabs: the header above [content], with the TV's tabs in it. [content] gets whether the header
 * shows the tabs, and what Back does on a tab's first page: climb to the header's open tab, then open Live.
 */
@Composable
internal fun TabPage(
    tv: Boolean,
    tabs: TabsModel,
    tabFocus: FocusRequester,
    settingsFocus: FocusRequester,
    headerReturn: HeaderReturn?,
    onHeaderFocusRequest: () -> Unit,
    refreshing: Boolean,
    notice: String?,
    onSettings: () -> Unit,
    onBackToLive: () -> Unit,
    focusHeaderIfIdle: Boolean,
    nowPlaying: MediaItem?,
    onNowPlaying: () -> Unit,
    content: @Composable (showsTabs: Boolean, tabBack: () -> Unit, headerFocused: Boolean) -> Unit,
) {
    val showsTabs = tv
    var pageFocused by remember { mutableStateOf(false) }
    val currentOnHeaderFocusRequest by rememberUpdatedState(onHeaderFocusRequest)
    // Keyed on nothing, so clearing the flag does not cancel the request; it runs on each return here.
    val nowPlayingFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val target = headerReturn
        if (target != null) {
            val button = when (target) {
                HeaderReturn.SETTINGS -> settingsFocus
                HeaderReturn.NOW_PLAYING -> nowPlayingFocus
                HeaderReturn.TAB -> tabFocus
            }
            if (showsTabs) {
                // The song button is gone once the music has ended, so its tab takes focus instead.
                if (!requestFocusWhenAttached(button)) requestFocusWhenAttached(tabFocus)
            } else {
                // The rail or bar keeps focus only when the page takes none of its own, as on a first arrival below.
                repeat(IDLE_FOCUS_FRAMES) { withFrameNanos { } }
                if (!pageFocused) requestFocusWhenAttached(button)
            }
            currentOnHeaderFocusRequest()
        } else if (focusHeaderIfIdle) {
            // A page that takes no focus of its own (Library's home, Recordings with nothing recorded) leaves it on
            // its tab, in the header on a TV and on the rail or bar elsewhere, so something is focused from the start
            // and the remote or a keyboard works at once.
            repeat(IDLE_FOCUS_FRAMES) { withFrameNanos { } }
            if (!pageFocused) requestFocusWhenAttached(tabFocus)
        }
    }
    var headerFocused by remember { mutableStateOf(false) }
    val tabBack: () -> Unit = {
        when (tabBackAction(hasHeader = showsTabs, headerFocused = headerFocused)) {
            TabBack.FOCUS_HEADER -> tabFocus.requestFocus()
            TabBack.OPEN_LIVE -> onBackToLive()
        }
    }
    Column(Modifier.onFocusChanged { pageFocused = it.hasFocus }) {
        Header(
            modifier = Modifier.onFocusChanged { headerFocused = it.hasFocus },
            refreshing = refreshing,
            notice = notice,
            onSettings = onSettings.takeIf { showsTabs },
            settingsFocus = settingsFocus,
            tabs = tabs.takeIf { showsTabs },
            tabFocus = tabFocus,
            nowPlaying = nowPlaying.takeIf { showsTabs },
            nowPlayingFocus = nowPlayingFocus,
            onNowPlaying = onNowPlaying,
        )
        content(showsTabs, tabBack, headerFocused)
    }
}

/** Frames a tab page gets to focus something of its own before its tab takes focus. */
private const val IDLE_FOCUS_FRAMES = 3

/**
 * Which destination is open, whether Library and Recordings are offered, and what picking one does. A rail also
 * lists [libraries] under Library: [libraryOpen] is the one whose grid is open, and [onLibrary] opens one. Search
 * sits beside them while there is something to search ([searchShown]): [searchOpen] while its page is the one on
 * top, which [onSearch] opens.
 */
internal class TabsModel(
    val selected: Tab,
    val libraryShown: Boolean,
    val recordingsShown: Boolean,
    val onTab: (Tab) -> Unit,
    val libraries: List<MediaLibrary> = emptyList(),
    val libraryOpen: String? = null,
    val onLibrary: (String) -> Unit = {},
    val searchShown: Boolean = false,
    val searchOpen: Boolean = false,
    val onSearch: () -> Unit = {},
)

/**
 * Live, Library, DVR, Search and Settings around [content]. A TV keeps them in the header, so the guide loses no
 * width and Up from its chips reaches them as it reaches the header's buttons; [TabPage] draws them there.
 * Elsewhere Material's navigation suite puts them in a short bar on a compact window and a rail on a larger one,
 * and slides it away while [shown] is false, over the full-screen players. A rail with the height for them lists
 * the libraries under Library, each a destination of its own ([railLibraries]).
 */
@Composable
internal fun TopLevel(
    tv: Boolean,
    tabs: TabsModel,
    shown: Boolean,
    settingsOpen: Boolean,
    onSettings: () -> Unit,
    settingsFocus: FocusRequester,
    tabFocus: FocusRequester,
    content: @Composable () -> Unit,
) {
    if (tv) {
        content()
        return
    }
    val type = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfoV2())
    val state =
        rememberNavigationSuiteScaffoldState(
            if (shown) NavigationSuiteScaffoldValue.Visible else NavigationSuiteScaffoldValue.Hidden,
        )
    LaunchedEffect(shown) { if (shown) state.show() else state.hide() }
    NavigationSuiteScaffold(
        navigationItems = {
            val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
            val listed = railLibraries(type, height, tabs)
            // Settings and Search are the selected item while open, over whichever tab they were opened on.
            val away = settingsOpen || tabs.searchOpen
            val libraryOpen = tabs.libraryOpen.takeIf { open -> !away && listed.any { it.id == open } }
            // A library listed in the rail is the selected item while its grid is open, in Library's place.
            val selected = tabs.selected.takeUnless { away || libraryOpen != null }
            TabItem(Tab.LIVE, SofaIcons.LiveTv, Res.string.nav_live, selected, tabs.onTab, tabFocus, type)
            if (tabs.libraryShown) {
                TabItem(
                    Tab.LIBRARY,
                    SofaIcons.VideoLibrary,
                    Res.string.nav_library,
                    selected,
                    tabs.onTab,
                    tabFocus,
                    type,
                )
                for (library in listed) {
                    LibraryItem(library, library.id == libraryOpen, tabs.onLibrary, tabFocus, type)
                }
            }
            if (tabs.recordingsShown) {
                TabItem(
                    Tab.RECORDINGS,
                    SofaIcons.Recordings,
                    Res.string.nav_dvr,
                    selected,
                    tabs.onTab,
                    tabFocus,
                    type,
                )
            }
            if (tabs.searchShown) SearchItem(tabs.searchOpen && !settingsOpen, tabs.onSearch, tabFocus, type)
            NavigationSuiteItem(
                selected = settingsOpen,
                onClick = { if (!settingsOpen) onSettings() },
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { NavigationLabel(stringResource(Res.string.nav_settings)) },
                modifier = Modifier.focusRequester(settingsFocus),
                navigationSuiteType = type,
            )
        },
        navigationSuiteType = type,
        containerColor = MaterialTheme.colorScheme.background,
        state = state,
        content = content,
    )
}

/** One tab in the navigation suite, selected when it is the [open] one, which then takes [tabFocus]. */
@Composable
private fun TabItem(
    tab: Tab,
    icon: ImageVector,
    label: StringResource,
    open: Tab?,
    onTab: (Tab) -> Unit,
    tabFocus: FocusRequester,
    type: NavigationSuiteType,
) {
    val selected = open == tab
    NavigationSuiteItem(
        selected = selected,
        onClick = { onTab(tab) },
        modifier = if (selected) Modifier.focusRequester(tabFocus) else Modifier,
        icon = { Icon(icon, contentDescription = null) },
        label = { NavigationLabel(stringResource(label)) },
        navigationSuiteType = type,
    )
}

/** Search in the navigation suite, selected while its page is [open], when it takes [tabFocus]. */
@Composable
private fun SearchItem(open: Boolean, onSearch: () -> Unit, tabFocus: FocusRequester, type: NavigationSuiteType) {
    NavigationSuiteItem(
        selected = open,
        onClick = { if (!open) onSearch() },
        modifier = if (open) Modifier.focusRequester(tabFocus) else Modifier,
        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
        label = { NavigationLabel(stringResource(Res.string.nav_search)) },
        navigationSuiteType = type,
    )
}

/** One library in the rail, under Library; the one whose grid is [open] is selected and takes [tabFocus]. */
@Composable
private fun LibraryItem(
    library: MediaLibrary,
    open: Boolean,
    onLibrary: (String) -> Unit,
    tabFocus: FocusRequester,
    type: NavigationSuiteType,
) {
    NavigationSuiteItem(
        selected = open,
        onClick = { onLibrary(library.id) },
        modifier = if (open) Modifier.focusRequester(tabFocus) else Modifier,
        icon = { Icon(library.kind.railIcon, contentDescription = null) },
        label = { NavigationLabel(library.name, ellipsis = true) },
        navigationSuiteType = type,
    )
}

/**
 * A navigation item's label on one line. The theme's TV-sized label nearly fills the rail's 96 dp item, so at a
 * larger text size a label runs past the item's edge rather than break mid-word. A library's name is the server
 * owner's and can be any length, so it ends in an [ellipsis] instead.
 */
@Composable
private fun NavigationLabel(text: String, ellipsis: Boolean = false) {
    if (ellipsis) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        Text(text, softWrap = false, maxLines = 1)
    }
}

/**
 * The libraries a navigation suite of [type] lists under Library: every one in a rail, unless the server has more
 * than [RAIL_LIBRARIES_MAX] or a window [height] tall has no room for them above Settings, since a rail does not
 * scroll; none in a bar, where the Library home's chips open them.
 */
internal fun railLibraries(type: NavigationSuiteType, height: Dp, tabs: TabsModel): List<MediaLibrary> {
    val rail = type == NavigationSuiteType.NavigationRail ||
        type == NavigationSuiteType.WideNavigationRailCollapsed ||
        type == NavigationSuiteType.WideNavigationRailExpanded
    // Live, Library and Settings, with DVR and Search where they show.
    val others = 3 + (if (tabs.recordingsShown) 1 else 0) + (if (tabs.searchShown) 1 else 0)
    val needed = RAIL_MARGINS + RAIL_ITEM_PITCH * (others + tabs.libraries.size)
    val fits = rail && tabs.libraryShown && tabs.libraries.size <= RAIL_LIBRARIES_MAX && height >= needed
    return if (fits) tabs.libraries else emptyList()
}

/** The most libraries a rail lists; Material's rail holds up to seven destinations in all. */
private const val RAIL_LIBRARIES_MAX = 4

/** From one rail item's top to the next one's, as Material lays them out. */
private val RAIL_ITEM_PITCH = 68.dp

/** The rail's own space above its first item and under its last, and a system's bars, which the height includes. */
private val RAIL_MARGINS = 96.dp

private val LibraryKind.railIcon: ImageVector
    get() = when (this) {
        LibraryKind.MOVIES -> SofaIcons.Movie
        LibraryKind.SHOWS -> SofaIcons.Tv
        LibraryKind.MUSIC -> SofaIcons.Audiotrack
        LibraryKind.VIDEOS, LibraryKind.OTHER -> SofaIcons.VideoLibrary
    }

/** The header the tab pages and the top level share: the tabs, the focus on them and on Settings, and Settings. */
internal class TabChrome(
    val tv: Boolean,
    val tabs: TabsModel,
    val tabFocus: FocusRequester,
    val settingsFocus: FocusRequester,
    val onSettings: () -> Unit,
)

/** The header for the tabs [offered] now; picking Library reads its shelves again through [onLibraryOpen]. */
@Composable
internal fun rememberTabChrome(
    shell: AppShellState,
    offered: TabsOffered,
    tv: Boolean,
    onLibraryOpen: () -> Unit,
): TabChrome {
    val settingsFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    val tabs = TabsModel(
        selected = shell.nav.tab,
        libraryShown = offered.library,
        recordingsShown = offered.recordings,
        onTab = { picked ->
            // The library's shelves are read again each time its tab is opened, as the recordings are.
            if (picked == Tab.LIBRARY && shell.nav.tab != Tab.LIBRARY) onLibraryOpen()
            shell.openTab(picked)
        },
        libraries = offered.libraries,
        libraryOpen = shell.nav.libraryOpen,
        onLibrary = { picked ->
            if (shell.nav.tab != Tab.LIBRARY) onLibraryOpen()
            shell.openLibrary(picked)
        },
        searchShown = offered.search,
        searchOpen = shell.top is Route.Search,
        onSearch = shell::openSearch,
    )
    return TabChrome(tv, tabs, tabFocus, settingsFocus, onSettings = { shell.push(Route.Settings) })
}
