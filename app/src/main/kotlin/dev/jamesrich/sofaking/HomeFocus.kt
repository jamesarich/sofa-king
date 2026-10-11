package dev.jamesrich.sofaking

/** Where focus starts on the home screen: Settings with no server to talk to, the grid once channels are shown. */
internal enum class HomeFocus { SETTINGS, GRID, NONE }

internal fun homeFocusFor(state: LineupState): HomeFocus = when (state) {
    LineupState.Unconfigured -> HomeFocus.SETTINGS
    is LineupState.Ready -> if (state.lineup.channels.isEmpty()) HomeFocus.NONE else HomeFocus.GRID
    LineupState.Loading, is LineupState.Failed -> HomeFocus.NONE
}
