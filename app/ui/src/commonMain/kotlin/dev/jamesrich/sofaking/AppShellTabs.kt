package dev.jamesrich.sofaking

/** A tab picked in the header or the navigation bar; Live comes back on the channel last watched. */
internal fun AppShellState.openTab(picked: Tab) {
    nav =
        nav.closeSettings().open(
            picked,
            if (picked == Tab.LIVE) {
                Arrival(returnedFrom = history.watching)
            } else {
                Arrival()
            },
        )
}

/** Leaving Settings lands on the page it was opened from, with focus back on the header's Settings button. */
internal fun AppShellState.leaveSettings() {
    headerReturn = HeaderReturn.SETTINGS
    nav = nav.closeSettings().let { it.copy(arrival = it.arrival.copy(inHeader = true)) }
}

/** Back from another tab's pill: Live opens with focus kept on its pill, so one more Back exits. */
internal fun AppShellState.backToLive() {
    headerReturn = HeaderReturn.TAB
    nav = nav.open(Tab.LIVE, Arrival(returnedFrom = history.watching, inHeader = true))
}

/** The Guide key from the Library or Recordings pages: the guide at Now, on the channel last watched. */
internal fun AppShellState.guideFromElsewhere() {
    nav = nav.open(Tab.LIVE, Arrival(returnedFrom = history.watching, atNow = true))
}
