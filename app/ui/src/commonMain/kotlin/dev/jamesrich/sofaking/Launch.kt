package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.StartupTarget

/** Where a cold start lands once the lineup and settings are known. */
internal sealed interface LaunchTarget {
    /** Still loading, or settings not read yet: show the loader and decide later. */
    data object Undecided : LaunchTarget
    data object Guide : LaunchTarget
    data class Player(val guideId: String) : LaunchTarget
}

/**
 * The startup setting's channel (the last watched, or a chosen one) opens full screen when the lineup has it;
 * a first run, a channel the lineup lost, a startup of the guide, an unconfigured or unreachable server all
 * open the guide. Loading with nothing to show stays undecided.
 */
internal fun launchTarget(state: LineupState, settings: AppSettings?): LaunchTarget = when {
    settings == null || state is LineupState.Loading -> LaunchTarget.Undecided
    state is LineupState.Ready -> {
        val wanted = when (settings.startup) {
            StartupTarget.LAST_CHANNEL -> settings.lastChannel
            StartupTarget.CHANNEL -> settings.startupChannel
            StartupTarget.GUIDE -> null
        }
        if (wanted != null && state.lineup.channels.any { it.guideId == wanted }) LaunchTarget.Player(wanted) else LaunchTarget.Guide
    }
    else -> LaunchTarget.Guide
}
