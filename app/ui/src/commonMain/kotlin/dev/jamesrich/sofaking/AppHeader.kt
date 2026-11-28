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
import androidx.compose.ui.text.style.TextOverflow
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
 * The TV's destinations in the header, as Google TV shows them: Material tabs, the open one on a pill, every one with
 * the D-pad focus cue. Focus coming up from the page lands on the open one ([tabFocus]).
 */
@Composable
private fun HeaderTabs(tabs: TabsModel, tabFocus: FocusRequester) {
    Row(
        Modifier.focusProperties { onEnter = { tabFocus.requestFocus() } }.focusGroup().selectableGroup(),
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

/**
 * One destination, Material's tab: the open one sits on a pill in the secondary container. A tab row's indicator is
 * an underline across the whole row, so the pill is drawn behind the tab. As wide as its label and as tall as a
 * button, so the header keeps its layout and height.
 */
@Composable
private fun HeaderTab(label: String, selected: Boolean, tabFocus: FocusRequester, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MaterialTab(
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .then(if (selected) Modifier.focusRequester(tabFocus) else Modifier)
            .tvFocus(CircleShape)
            // A tab fills the width it is given, as it would in a tab row; here it is as wide as its label.
            .width(IntrinsicSize.Max)
            .height(ButtonDefaults.MinHeight)
            .clip(CircleShape)
            .background(if (selected) colors.secondaryContainer else Color.Transparent),
        selectedContentColor = colors.onSecondaryContainer,
        unselectedContentColor = colors.onSurfaceVariant,
        text = { Text(label, style = MaterialTheme.typography.titleMedium) },
    )
}

/**
 * App bar above the guide and the library, with the TV's [tabs], inside the side overscan margins ([TabPage] keeps the
 * top one); the mark sits within the buttons' height so no grid row is lost. Search is an icon beside Settings, so
 * the status keeps its room on a 960 dp screen.
 */
@Composable
internal fun Header(
    refreshing: Boolean,
    notice: String?,
    onSettings: (() -> Unit)?,
    settingsFocus: FocusRequester,
    modifier: Modifier = Modifier,
    tabs: TabsModel? = null,
    tabFocus: FocusRequester = remember { FocusRequester() },
    nowPlaying: MediaItem? = null,
    nowPlayingFocus: FocusRequester = remember { FocusRequester() },
    onNowPlaying: () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().screenPadding(WindowInsetsSides.Horizontal),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A phone on its side has height for only a few guide rows, so the mark and name give theirs to the page.
        val profile = LocalLayoutProfile.current
        if (profile.tv || !profile.compactHeight) {
            LogoMark(40.dp)
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        }
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
                    modifier = Modifier.announcesChanges(),
                )
            }
        }
        nowPlaying?.let { NowPlayingButton(it, nowPlayingFocus, onNowPlaying) }
        if (tabs?.searchShown == true) {
            SofaIconAction(tabs.onSearch, Icons.Filled.Search, stringResource(Res.string.nav_search))
        }
        // Settings is the header's other button; the lineup, library and recordings reload by themselves, and
        // Settings › Server has Reload.
        onSettings?.let {
            SofaButton(onClick = it, modifier = Modifier.focusRequester(settingsFocus)) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(Res.string.settings))
            }
        }
    }
}

/** A TV has no bar under its pages for the music, so the [song] playing has a button that opens Now playing. */
@Composable
private fun NowPlayingButton(song: MediaItem, focus: FocusRequester, onClick: () -> Unit) {
    SofaButton(onClick = onClick, modifier = Modifier.widthIn(max = NOW_PLAYING_WIDTH).focusRequester(focus)) {
        Icon(SofaIcons.Audiotrack, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The widest the header's Now playing button gets, so a long song title leaves room for the status. */
private val NOW_PLAYING_WIDTH = 320.dp
