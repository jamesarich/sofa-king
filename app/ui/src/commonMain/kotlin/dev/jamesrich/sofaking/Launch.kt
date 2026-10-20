package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.data.TopLevelTab

/** Where a cold start lands once the lineup and settings are known. */
internal sealed interface LaunchTarget {
    /** Still loading, or settings not read yet: show the loader and decide later. */
    data object Undecided : LaunchTarget
    data object Guide : LaunchTarget
    data class Player(val guideId: String) : LaunchTarget
    /** Library or Recordings, on the tab's first page with nothing playing. */
    data class OpenTab(val tab: Tab) : LaunchTarget
}

/**
 * The startup setting decides: where the viewer left off reopens the tab the app was last on, which on Live is the
 * last watched channel full screen; the library opens the Library tab; a chosen channel opens full screen. A first
 * run, a channel the lineup lost, a startup of the guide, an unconfigured or unreachable server all open the guide.
 * Loading with nothing to show stays undecided.
 */
internal fun launchTarget(state: LineupState, settings: AppSettings?): LaunchTarget = when {
    settings == null || state is LineupState.Loading -> LaunchTarget.Undecided
    state is LineupState.Ready -> {
        val tab = when (settings.startup) {
            StartupTarget.RESUME -> settings.lastTab.tab
            StartupTarget.LIBRARY -> Tab.LIBRARY
            StartupTarget.GUIDE, StartupTarget.CHANNEL -> Tab.LIVE
        }
        val wanted = when (settings.startup) {
            StartupTarget.RESUME -> settings.lastChannel
            StartupTarget.CHANNEL -> settings.startupChannel
            StartupTarget.GUIDE, StartupTarget.LIBRARY -> null
        }
        when {
            tab != Tab.LIVE -> LaunchTarget.OpenTab(tab)
            wanted != null && state.lineup.channels.any { it.guideId == wanted } -> LaunchTarget.Player(wanted)
            else -> LaunchTarget.Guide
        }
    }
    else -> LaunchTarget.Guide
}

/** The settings' name for a tab, and back. */
internal val TopLevelTab.tab: Tab
    get() = when (this) {
        TopLevelTab.LIVE -> Tab.LIVE
        TopLevelTab.LIBRARY -> Tab.LIBRARY
        TopLevelTab.RECORDINGS -> Tab.RECORDINGS
    }

internal val Tab.topLevel: TopLevelTab
    get() = when (this) {
        Tab.LIVE -> TopLevelTab.LIVE
        Tab.LIBRARY -> TopLevelTab.LIBRARY
        Tab.RECORDINGS -> TopLevelTab.RECORDINGS
    }
