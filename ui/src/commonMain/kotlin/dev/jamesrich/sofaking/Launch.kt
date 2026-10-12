package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppSettings

/** Where a cold start lands once the lineup and settings are known. */
internal sealed interface LaunchTarget {
    /** Still loading, or settings not read yet: show the loader and decide later. */
    data object Undecided : LaunchTarget
    data object Guide : LaunchTarget
    data class Player(val guideId: String) : LaunchTarget
}

/**
 * The last channel watched opens full screen when the lineup has it; a first run, a channel the lineup
 * lost, an unconfigured or unreachable server all open the guide. Loading with nothing to show stays undecided.
 */
internal fun launchTarget(state: LineupState, settings: AppSettings?): LaunchTarget = when {
    settings == null || state is LineupState.Loading -> LaunchTarget.Undecided
    state is LineupState.Ready -> {
        val last = settings.lastChannel
        if (last != null && state.lineup.channels.any { it.guideId == last }) LaunchTarget.Player(last) else LaunchTarget.Guide
    }
    else -> LaunchTarget.Guide
}
