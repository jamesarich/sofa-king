package dev.jamesrich.sofaking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.app_name
import dev.jamesrich.sofaking.resources.nav_dvr
import dev.jamesrich.sofaking.resources.nav_library
import dev.jamesrich.sofaking.resources.nav_live
import dev.jamesrich.sofaking.resources.nav_settings
import dev.jamesrich.sofaking.resources.refreshing
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.ui.KidsBadge
import dev.jamesrich.sofaking.ui.LocalKidsMode
import dev.jamesrich.sofaking.ui.LogoMark
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.SofaLoaderLabel
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

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
    content: @Composable (showsTabs: Boolean, tabBack: () -> Unit, headerFocused: Boolean) -> Unit,
) {
    val showsTabs = tv
    var pageFocused by remember { mutableStateOf(false) }
    val currentOnHeaderFocusRequest by rememberUpdatedState(onHeaderFocusRequest)
    // Keyed on nothing, so clearing the flag does not cancel the request; it runs on each return here.
    LaunchedEffect(Unit) {
        val target = headerReturn
        if (target != null) {
            val button = if (target == HeaderReturn.SETTINGS) settingsFocus else tabFocus
            if (showsTabs) {
                requestFocusWhenAttached(button)
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
        )
        content(showsTabs, tabBack, headerFocused)
    }
}

/** Frames a tab page gets to focus something of its own before its tab takes focus. */
private const val IDLE_FOCUS_FRAMES = 3

/** Which destination is open, whether Library and Recordings are offered, and what picking one does. */
internal class TabsModel(
    val selected: Tab,
    val libraryShown: Boolean,
    val recordingsShown: Boolean,
    val onTab: (Tab) -> Unit,
)

/**
 * Live, Library, DVR and Settings around [content]. A TV keeps them in the header, so the guide loses no
 * width and Up from its chips reaches them as it reaches the header's buttons; [TabPage] draws them there.
 * Elsewhere Material's navigation suite puts them in a short bar on a compact window and a rail on a larger one,
 * and slides it away while [shown] is false, over the full-screen players.
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
            val selected = tabs.selected.takeUnless { settingsOpen }
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

/**
 * A navigation item's label on one line. The theme's TV-sized label nearly fills the rail's 96 dp item, so at a
 * larger text size a label runs past the item's edge rather than break mid-word.
 */
@Composable
private fun NavigationLabel(text: String) {
    Text(text, softWrap = false, maxLines = 1)
}

/**
 * The TV's destinations as pills in the header: the open one filled, every one with the D-pad focus cue. Focus
 * coming up from the page lands on the open one ([tabFocus]).
 */
@Composable
private fun HeaderTabs(tabs: TabsModel, tabFocus: FocusRequester) {
    Row(
        Modifier.focusProperties { onEnter = { tabFocus.requestFocus() } }.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderTab(stringResource(Res.string.nav_live), tabs.selected == Tab.LIVE, tabFocus) { tabs.onTab(Tab.LIVE) }
        if (tabs.libraryShown) {
            HeaderTab(
                stringResource(Res.string.nav_library),
                tabs.selected == Tab.LIBRARY,
                tabFocus,
            ) {
                tabs.onTab(Tab.LIBRARY)
            }
        }
        if (tabs.recordingsShown) {
            HeaderTab(
                stringResource(Res.string.nav_dvr),
                tabs.selected == Tab.RECORDINGS,
                tabFocus,
            ) {
                tabs.onTab(Tab.RECORDINGS)
            }
        }
    }
}

@Composable
private fun HeaderTab(label: String, selected: Boolean, tabFocus: FocusRequester, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.semantics {
            this.selected = selected
        }.then(if (selected) Modifier.focusRequester(tabFocus) else Modifier).tvFocus(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

/**
 * App bar above the guide and the library, with the TV's [tabs], inside the overscan margins; the mark sits within the
 * buttons' height so no grid row is lost.
 */
@Composable
private fun Header(
    refreshing: Boolean,
    notice: String?,
    onSettings: (() -> Unit)?,
    settingsFocus: FocusRequester,
    modifier: Modifier = Modifier,
    tabs: TabsModel? = null,
    tabFocus: FocusRequester = remember { FocusRequester() },
) {
    Row(
        modifier.fillMaxWidth().screenPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(40.dp)
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        if (LocalKidsMode.current) KidsBadge()
        tabs?.let { HeaderTabs(it, tabFocus) }
        // Status sits right of the tabs in whatever Settings leaves, so a long notice ellipsizes rather than
        // squeezing the button.
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The lineup on screen stays usable while it refreshes; the loader only says new data is on its way.
            AnimatedVisibility(
                visible = refreshing,
                enter = PopIn,
                exit = PopOut,
            ) {
                SofaLoaderLabel(stringResource(Res.string.refreshing))
            }
            // The last text is kept so the notice reads while it fades out.
            var lastNotice by remember { mutableStateOf("") }
            LaunchedEffect(notice) { if (notice != null) lastNotice = notice }
            AnimatedVisibility(
                visible = notice != null,
                enter = PopIn,
                exit = PopOut,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    notice ?: lastNotice,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Settings is the header's one button; the lineup, library and recordings reload by themselves, and
        // Settings › Server has Reload.
        onSettings?.let {
            SofaButton(onClick = it, modifier = Modifier.focusRequester(settingsFocus)) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(Res.string.settings))
            }
        }
    }
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
    val tabs = TabsModel(shell.nav.tab, offered.library, offered.recordings) { picked ->
        // The library's shelves are read again each time its tab is opened, as the recordings are.
        if (picked == Tab.LIBRARY && shell.nav.tab != Tab.LIBRARY) onLibraryOpen()
        shell.openTab(picked)
    }
    return TabChrome(tv, tabs, tabFocus, settingsFocus, onSettings = { shell.push(Route.Settings) })
}
