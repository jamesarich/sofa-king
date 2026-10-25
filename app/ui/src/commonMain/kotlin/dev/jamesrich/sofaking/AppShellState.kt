package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.data.ServerCapabilities
import dev.jamesrich.sofaking.ui.player.AutoplayRun
import dev.jamesrich.sofaking.ui.player.SleepTimer
import dev.jamesrich.sofaking.ui.player.VideoZoom
import dev.jamesrich.sofaking.ui.settings.SettingsLock

/** Which header button takes focus when a tab's page comes back: its tab (after Back to Live) or Settings. */
internal enum class HeaderReturn { TAB, SETTINGS }

/**
 * The app shell's UI state and the moves between pages, kept out of the composables so it can be tested: where the
 * viewer is ([nav]), the channels watched ([history]), whether the cold start has been decided and Settings unlocked,
 * and the header's focus and notice. The back stacks, the history and both flags survive a restore; the rest does not.
 */
@Stable
internal class AppShellState(
    nav: MutableState<Navigation> = mutableStateOf(Navigation()),
    history: MutableState<WatchHistory> = mutableStateOf(WatchHistory()),
    launchDecided: MutableState<Boolean> = mutableStateOf(false),
    settingsUnlocked: MutableState<Boolean> = mutableStateOf(false),
) {
    /** A back stack per tab, shown by Navigation 3. */
    var nav by nav

    /**
     * The channel the player has tuned, so the guide knows which one it came back from, and the one before it for Last.
     */
    var history by history

    /** The cold start has picked its page, once per process; the guide and preview wait for it. */
    var launchDecided by launchDecided
        private set

    /**
     * The PIN, once entered, opens Settings and the pages behind it until the viewer
     * leaves for the guide or the player.
     */
    var settingsUnlocked by settingsUnlocked

    /** Where focus goes when a tab's page comes back, or null once it has been asked for. */
    var headerReturn by mutableStateOf<HeaderReturn?>(null)

    /** A short word in the header, such as a channel the player found gone; cleared after a few seconds. */
    var notice by mutableStateOf<String?>(null)

    /** The players' zoom, which lasts from channel to channel and item to item until the app closes. */
    var videoZoom by mutableStateOf(VideoZoom.FIT)

    /** The Settings lock's count of wrong tries, which outlives each trip to Settings. */
    val settingsLock = SettingsLock()

    /**
     * The players' sleep timer, which lasts through zaps and Up next until the viewer leaves the full-screen player.
     */
    val sleep = SleepTimer()

    /** The items Up next played by itself in a row, for "Still watching?". */
    val autoplays = AutoplayRun()

    /** The page on top of the open tab. */
    val top: Route get() = nav.top

    fun push(route: Route, arrival: Arrival = Arrival()) {
        nav = nav.push(route, arrival)
    }

    /** Back one page. The player hands the guide the channel it was left on, which may not be the one it opened. */
    fun back() {
        nav = nav.pop(if (nav.top is Route.Player) history.watching else nav.top.focusKey)
    }

    /** From the player to the guide by its Guide or Search key: the guide opens at Now or with search open. */
    fun toGuide(arrival: Arrival) {
        nav = nav.pop(history.watching).let { it.copy(arrival = arrival.copy(returnedFrom = it.arrival.returnedFrom)) }
    }

    /** From a recording still being written to its channel, live; the player decides what it is watching. */
    fun toLive(guideId: String) {
        nav = nav.toLive(guideId)
    }

    /** The next episode replaces the one that ended, so Back still leaves for the page the first was opened from. */
    fun replaceTop(route: Route) {
        nav = nav.replaceTop(route)
    }

    /** Back to the first page of every tab, as after pairing with or saving a new server. */
    fun startOver() {
        nav = Navigation()
    }

    /** A cold start opens [launch]'s page, once; an undecided start waits for the lineup and settings. */
    fun decideLaunch(launch: LaunchTarget) {
        if (launchDecided || launch == LaunchTarget.Undecided) return
        launchDecided = true
        when (launch) {
            is LaunchTarget.Player -> nav = Navigation().push(Route.Player(launch.guideId))
            is LaunchTarget.OpenTab -> nav = Navigation().open(launch.tab)
            else -> Unit
        }
    }

    /**
     * A `sofaking://vod` link, from the home screen's Watch Next row, plays [itemId] over the Library tab, so Back
     * leaves for the Library, and takes the place of an item already playing there. On a cold start it is the
     * start's page, in place of the channel last watched.
     */
    fun playItem(itemId: String) {
        val from = if (launchDecided) nav.closeSettings() else Navigation()
        launchDecided = true
        val library = if (from.tab == Tab.LIBRARY) from else from.open(Tab.LIBRARY)
        nav = if (library.top is Route.Vod) library.replaceTop(Route.Vod(itemId)) else library.push(Route.Vod(itemId))
    }

    /** The guide and the player lock Settings again; the pages behind Settings keep it open. */
    fun relockUnlessInSettings() {
        if (!top.isSettings) settingsUnlocked = false
    }
}

/** An [AppShellState] whose back stacks, history and flags survive a configuration change and process death. */
@Composable
internal fun rememberAppShellState(): AppShellState {
    val nav = rememberSerializable { mutableStateOf(Navigation()) }
    val history = rememberSerializable { mutableStateOf(WatchHistory()) }
    val launchDecided = rememberSaveable { mutableStateOf(false) }
    val settingsUnlocked = rememberSaveable { mutableStateOf(false) }
    return remember { AppShellState(nav, history, launchDecided, settingsUnlocked) }
}

/** Which of Library and Recordings the header offers. */
internal data class TabsOffered(val library: Boolean, val recordings: Boolean)

/**
 * The Library tab shows when the server's bootstrap says it serves libraries and it has at least one, and the
 * Recordings tab when it records. A start on either shows its tab before the bootstrap answers.
 */
internal fun tabsOffered(
    capabilities: ServerCapabilities,
    open: Tab,
    libraryHome: LibraryHomeState,
    recordings: RecordingsState,
): TabsOffered = TabsOffered(
    library = (capabilities.library || open == Tab.LIBRARY) && when (libraryHome) {
        LibraryHomeState.Unavailable -> false
        is LibraryHomeState.Ready -> libraryHome.home.libraries.isNotEmpty()
        else -> true
    },
    recordings = capabilities.recordings || (open == Tab.RECORDINGS && recordings != RecordingsState.Unavailable),
)
