package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.jamesrich.sofaking.ui.player.LivePlayer

/**
 * The page for [route], drawn by its destination (LiveDestinations, LibraryDestinations, RecordingsDestinations,
 * SettingsDestinations), which reads the state it shows itself. [refreshing] is the lineup reloading under the guide.
 */
@Composable
internal fun RouteDestination(
    route: Route,
    shell: AppShellState,
    vm: LineupViewModel,
    libraryVm: LibraryViewModel,
    recordingsVm: RecordingsViewModel,
    player: LivePlayer,
    offered: TabsOffered,
    chrome: TabChrome,
    hideSpoilers: Boolean,
    refreshing: Boolean,
) {
    val arrival = if (route == shell.nav.top) shell.nav.arrival else Arrival()
    when (route) {
        Route.Settings -> SettingsDestination(arrival, shell, vm, libraryVm, offered)

        Route.Channels, Route.StartupChannel -> ChannelsDestination(route, shell, vm)

        is Route.Player -> PlayerDestination(route, shell, vm, player)

        is Route.Multiview -> MultiviewDestination(route, shell, vm, player)

        is Route.Vod -> VodDestination(route, shell, vm, libraryVm, player)

        is Route.Recording -> RecordingDestination(route, shell, vm, recordingsVm, player)

        is Route.LibraryItem -> LibraryItemDestination(route, arrival, shell, libraryVm, hideSpoilers)

        Route.Guide, Route.OnLater, Route.Library, is Route.LibraryGrid, is Route.LibrarySearch, Route.Recordings ->
            TabDestination(route, arrival, shell, vm, libraryVm, recordingsVm, player, chrome, refreshing)
    }
}

/** A page under the tabs: the guide, On Later, the library's pages or the recordings, with the header over it. */
@Composable
private fun TabDestination(
    route: Route,
    arrival: Arrival,
    shell: AppShellState,
    vm: LineupViewModel,
    libraryVm: LibraryViewModel,
    recordingsVm: RecordingsViewModel,
    player: LivePlayer,
    chrome: TabChrome,
    refreshing: Boolean,
) {
    TabPage(
        tv = chrome.tv,
        tabs = chrome.tabs,
        tabFocus = chrome.tabFocus,
        settingsFocus = chrome.settingsFocus,
        headerReturn = shell.headerReturn,
        onHeaderFocusRequest = { shell.headerReturn = null },
        refreshing = refreshing,
        notice = shell.notice,
        onSettings = chrome.onSettings,
        onBackToLive = shell::backToLive,
        focusHeaderIfIdle = (route == Route.Library || route == Route.Recordings) && arrival.returnedFrom == null,
    ) { showsTabs, tabBack, headerFocused ->
        when (route) {
            Route.Guide -> GuideDestination(
                arrival,
                shell,
                vm,
                player,
                chrome.settingsFocus,
                headerFocus = chrome.tabFocus.takeIf { showsTabs },
            )

            Route.OnLater -> OnLaterDestination(shell, vm)

            Route.Library -> LibraryHomeDestination(arrival, shell, libraryVm, tabBack, headerFocused)

            Route.Recordings -> RecordingsDestination(arrival, shell, recordingsVm, tabBack, headerFocused)

            is Route.LibrarySearch -> LibrarySearchDestination(route, arrival, shell, libraryVm)

            is Route.LibraryGrid -> LibraryGridDestination(route, arrival, shell, libraryVm)

            else -> Unit
        }
    }
}
