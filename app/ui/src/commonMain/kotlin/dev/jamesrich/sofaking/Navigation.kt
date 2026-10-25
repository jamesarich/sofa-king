package dev.jamesrich.sofaking

import androidx.navigation3.runtime.NavKey
import dev.jamesrich.sofaking.ui.library.SEARCH_FOCUS_KEY
import dev.jamesrich.sofaking.ui.library.libraryFocusKey
import dev.jamesrich.sofaking.ui.player.ZapScope
import dev.jamesrich.sofaking.ui.settings.CHANNELS_FOCUS_KEY
import dev.jamesrich.sofaking.ui.settings.STARTUP_FOCUS_KEY
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** The app's top-level destinations; Settings sits apart, as a button on a TV and the last item elsewhere. */
@Serializable
internal enum class Tab { LIVE, LIBRARY, RECORDINGS }

/**
 * A page of the app, one entry on a tab's back stack. The page it was opened from is the entry below it, never a
 * field; one-shot details of an arrival travel beside the stack in [Arrival].
 */
@Serializable
internal sealed interface Route : NavKey {
    /** Names the entry: its saved state is kept under it, and a back stack never holds two entries with one id. */
    val id: String

    @Serializable
    data object Guide : Route {
        override val id: String get() = "guide"
    }

    /** On Later: the movies, sports, kids' programs and premieres coming up, opened from the guide. */
    @Serializable
    data object OnLater : Route {
        override val id: String get() = "on-later"
    }

    /**
     * A live channel full screen; [scope] is the guide filter it was picked from, which Up and Down stay in.
     * [unattended] marks one the guide opened by itself, which keeps the screen on only once a key is pressed.
     */
    @Serializable
    data class Player(
        val guideId: String,
        @Transient val scope: ZapScope? = null,
        @Transient val unattended: Boolean = false,
    ) : Route {
        override val id: String get() = "player"
    }

    /**
     * Multiview, opened from the live player on [guideIds]' first channel: a picker of two to four channels, then
     * the one picture the server draws them into.
     */
    @Serializable
    data class Multiview(val guideIds: List<String>) : Route {
        override val id: String get() = "multiview"
    }

    /**
     * A library item full screen; [fromStart] plays it from the beginning even when it is in progress, and [startMs],
     * from a link, from that point.
     */
    @Serializable
    data class Vod(val itemId: String, val fromStart: Boolean = false, val startMs: Long? = null) : Route {
        override val id: String get() = "vod:$itemId"
    }

    /** The Library tab's home. */
    @Serializable
    data object Library : Route {
        override val id: String get() = "library"
    }

    /** Search across the libraries; [query] searches for a name from an item's page. */
    @Serializable
    data class LibrarySearch(val query: String? = null) : Route {
        override val id: String get() = "search:${query.orEmpty()}"
    }

    /** One library's grid. */
    @Serializable
    data class LibraryGrid(val libraryId: String) : Route {
        override val id: String get() = "grid:$libraryId"
    }

    /** An item's page, full screen. */
    @Serializable
    data class LibraryItem(val itemId: String) : Route {
        override val id: String get() = "item:$itemId"
    }

    /** The Recordings tab. */
    @Serializable
    data object Recordings : Route {
        override val id: String get() = "recordings"
    }

    /** A recording full screen; [fromStart] plays it from the beginning even when it is in progress. */
    @Serializable
    data class Recording(val recordingId: String, val fromStart: Boolean = false) : Route {
        override val id: String get() = "recording:$recordingId"
    }

    @Serializable
    data object Settings : Route {
        override val id: String get() = "settings"
    }

    /** The channel manager behind Settings. */
    @Serializable
    data object Channels : Route {
        override val id: String get() = "channels"
    }

    /** The startup channel picker behind Settings. */
    @Serializable
    data object StartupChannel : Route {
        override val id: String get() = "startup-channel"
    }

    /** Settings and the pages behind it, where no stream plays. */
    val isSettings: Boolean get() = this is Settings || this is Channels || this is StartupChannel

    /** The guide, the live player and multiview, which hold a live stream. */
    val playsLive: Boolean get() = this is Guide || this is Player || this is Multiview

    /** The full screen players, the only pages that ask the display for a frame rate. */
    val playsFullScreen: Boolean get() = this is Player || this is Multiview || this is Vod || this is Recording

    /** The pages under the tabs: the header (or, away from a TV, the navigation bar or rail) shows above them. */
    val hasTabs: Boolean get() = this is Guide || this is OnLater || this is Library || this is LibrarySearch ||
        this is LibraryGrid || this is Recordings

    /** What the page below focuses when the viewer leaves this one; the player's channel is the app's to know. */
    val focusKey: String?
        get() = when (this) {
            is LibraryItem -> itemId
            is Vod -> itemId
            is Recording -> recordingId
            is LibraryGrid -> libraryFocusKey(libraryId)
            is LibrarySearch -> SEARCH_FOCUS_KEY
            Channels -> CHANNELS_FOCUS_KEY
            StartupChannel -> STARTUP_FOCUS_KEY
            else -> null
        }
}

/** The page each tab opens on, at the bottom of its back stack. */
internal val Tab.root: Route
    get() = when (this) {
        Tab.LIVE -> Route.Guide
        Tab.LIBRARY -> Route.Library
        Tab.RECORDINGS -> Route.Recordings
    }

/**
 * One-shot details for the page just arrived at, which a restore drops. [returnedFrom] is what the page focuses
 * as the viewer comes back to it (an item, a library's chip, the channel the player was left on, which the
 * preview must not reopen by itself until focus moves); [atNow] moves the guide's cursor to the current time (the
 * Guide key from the player); [search] opens the guide's search, or the library's by voice; [inHeader] keeps
 * focus in the header (on the open tab after Back from another tab, so one more Back exits; on Settings after
 * leaving it) until focus comes down into the page.
 */
internal data class Arrival(
    val returnedFrom: String? = null,
    val atNow: Boolean = false,
    val search: Boolean = false,
    val inHeader: Boolean = false,
)

/**
 * Where the viewer is: one back stack per tab, each starting at the tab's [root], and the open [tab]. Every move
 * returns a new value, so the app holds it as state and Navigation 3 shows [shown].
 */
@Serializable
internal data class Navigation(
    val tab: Tab = Tab.LIVE,
    val stacks: Map<Tab, List<Route>> = Tab.entries.associateWith { listOf(it.root) },
    @Transient val arrival: Arrival = Arrival(),
) {
    fun stack(tab: Tab): List<Route> = stacks[tab] ?: listOf(tab.root)

    val top: Route get() = stack(tab).last()

    /** Live's stack with the open tab's above it, so Back from another tab's first page lands on the guide. */
    val shown: List<Route> get() = if (tab == Tab.LIVE) stack(Tab.LIVE) else stack(Tab.LIVE) + stack(tab)

    /** Opens [route] on the open tab. A page already on the stack is returned to, with everything above it closed. */
    fun push(route: Route, arrival: Arrival = Arrival()): Navigation {
        val stack = stack(tab)
        val at = stack.indexOfFirst { it.id == route.id }
        val next = if (at >= 0) stack.take(at) + route else stack + route
        return copy(stacks = stacks + (tab to next), arrival = arrival)
    }

    /** Swaps the page on top for [route], as the next episode takes the player's place. */
    fun replaceTop(route: Route, arrival: Arrival = Arrival()): Navigation =
        pop(returnedFrom = null).push(route, arrival)

    /**
     * Back one page; the page below focuses [returnedFrom]. Back from a tab's first page opens Live, and on the
     * guide itself nothing changes: the guide decides its own Back, down to exiting the app.
     */
    fun pop(returnedFrom: String? = top.focusKey): Navigation {
        val stack = stack(tab)
        return when {
            stack.size > 1 -> copy(
                stacks = stacks + (tab to stack.dropLast(1)),
                arrival = Arrival(returnedFrom = returnedFrom),
            )

            tab != Tab.LIVE -> copy(tab = Tab.LIVE, arrival = Arrival())

            else -> this
        }
    }

    /** Closes Settings and the pages behind it, back to the page Settings was opened from. */
    fun closeSettings(): Navigation {
        var back = this
        while (back.top.isSettings && back.stack(back.tab).size > 1) back = back.pop(returnedFrom = null)
        return back
    }

    /**
     * Leaves the page on top, a recording still being written, for [guideId] live on the Live tab, in place of a
     * channel already playing there, so Back from it lands on the guide.
     */
    fun toLive(guideId: String): Navigation {
        val left = pop(returnedFrom = null)
        val live = if (left.tab == Tab.LIVE) left else left.copy(tab = Tab.LIVE)
        return live.push(Route.Player(guideId))
    }

    /**
     * Opens [tab] where the viewer left it. Picking the open tab again closes its pages back to its first one, as
     * Material's navigation bar does.
     */
    fun open(tab: Tab, arrival: Arrival = Arrival()): Navigation = when {
        tab != this.tab -> copy(tab = tab, arrival = arrival)
        stack(tab).size > 1 -> copy(stacks = stacks + (tab to listOf(tab.root)), arrival = arrival)
        else -> this
    }
}
