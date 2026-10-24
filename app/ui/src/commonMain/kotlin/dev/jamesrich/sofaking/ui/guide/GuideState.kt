package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEventType
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.HeldOkGuard
import dev.jamesrich.sofaking.ui.player.ChannelNumberInput
import dev.jamesrich.sofaking.ui.player.rememberChannelNumberInput
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How long the app is away before the guide comes back to now. */
internal val REALIGN_AFTER = 30.minutes

/** Asks the row for [guideId] to focus the cell covering window [minute]. [nonce] keeps each request distinct. */
internal data class GuideJump(val guideId: String, val minute: Long, val nonce: Int)

/**
 * The row a fresh guide starts on: the channel the player was just left on ([returnedFrom]), else the one last
 * focused in the guide ([lastFocused]) or watched ([lastWatched]); -1 when none is in [visible], so the first
 * row takes focus.
 */
internal fun initialGuideRow(
    visible: List<Channel>,
    returnedFrom: String?,
    lastFocused: String?,
    lastWatched: String?,
): Int {
    val wanted = returnedFrom ?: lastFocused ?: lastWatched ?: return -1
    return visible.indexOfFirst { it.guideId == wanted }
}

/**
 * True when the channel the player was left on is in the lineup but hidden by the filter
 * or search, which then give way.
 */
internal fun watchedIsHidden(returnedFrom: String?, visible: List<Channel>, all: List<Channel>): Boolean =
    returnedFrom != null && visible.none { it.guideId == returnedFrom } && all.any { it.guideId == returnedFrom }

/** Back from the player the cursor never points into the past: the listings start at [nowMinute] at the earliest. */
internal fun cursorOnReturn(cursor: Long, nowMinute: Long): Long = maxOf(cursor, nowMinute)

/**
 * The list item a [step] from item [current] lands on in a list of [size], or null when focus leaves the grid or
 * has no row to start from. A page stops at the grid's ends and never leaves it for the chips; a row step does.
 */
internal fun gridTarget(current: Int, step: GridStep, size: Int): Int? {
    if (current < 0 || size == 0) return null
    val target = (current + step.rows).let { if (step.paged) it.coerceIn(0, size - 1) else it }
    return target.takeIf { it in 0 until size }
}

/** The cell most recently focused in the grid, shown by the preview pane. */
internal data class FocusedCell(val channel: Channel, val cell: GuideCell)

/** What the guide's keys do beyond its own state: focus, scrolling, search by voice and the preview's rest. */
internal interface GuideControls {
    /** Back's climb, as [guideBackAction] decided it: closes search, or moves focus up to the chips or the header. */
    fun climbBack()

    /** A key was pressed: the preview's rest timers start over. */
    fun restartRest()

    /** The Guide key: the grid jumps to Now. */
    fun now()

    /** The Search key: search by voice, or the text field. */
    fun search()

    /** Info with the details open: they close and focus goes back to the program's cell. */
    fun closeDetails()

    /** Fast Forward or Rewind: the grid moves a day. */
    fun dayJump(shortcut: GuideShortcut)
}

/**
 * The guide's UI state and its moves, kept out of the composable so they can be tested: the window and the time
 * cursor, the filter and search, the focused cell and the row a move is heading for, the first focus, the open
 * details and the typed channel number. Keys reach it through [onKeyEvent] and [onGridKey]; what they do to focus
 * and scrolling goes through [GuideControls] and the callbacks they take. The window, the cursor, the filter,
 * search and the channel last focused survive a trip to the player; the rest does not.
 */
@Stable
internal class GuideState(
    liveStartSecond: MutableLongState = mutableLongStateOf(0L),
    dayOffset: MutableIntState = mutableIntStateOf(0),
    cursor: MutableLongState = mutableLongStateOf(0L),
    filter: MutableState<GuideFilter> = mutableStateOf(GuideFilter.All),
    searchOpen: MutableState<Boolean> = mutableStateOf(false),
    lastFocusedGuideId: MutableState<String?> = mutableStateOf(null),
    val searchText: TextFieldState = TextFieldState(),
    startSearch: Boolean = false,
    startInHeader: Boolean = false,
    val numbers: ChannelNumberInput = ChannelNumberInput(),
) {
    // The live window holds while now is inside it, so the grid does not jump every half hour; it rolls over
    // once the clock leaves it (a TV left on the guide for a day), along with the time cursor. The grid shows
    // the live window or one a whole number of days after it (Fast Forward), which comes back
    // to the live one when it rolls.
    var liveStartSecond by liveStartSecond
        private set
    var dayOffset by dayOffset

    val liveStart: Instant get() = Instant.fromEpochSeconds(liveStartSecond)

    /** Minutes into the window: Up and Down land on the cell under it. */
    var cursor by cursor
    var filter by filter
    var searchOpen by searchOpen
    var lastFocusedGuideId by lastFocusedGuideId
        private set

    // The Later line holding focus, by key; a cell taking focus clears it.
    var laterFocused by mutableStateOf<String?>(null)
        private set

    // Read only inside the preview band, so a focus move recomposes the band and not the whole grid.
    var focused by mutableStateOf<FocusedCell?>(null)
    var gridFocused by mutableStateOf(false)
    var chipsFocused by mutableStateOf(false)

    /** Bumped by each input that restarts the preview's rest, which the rest timers watch. */
    var dwellNonce by mutableIntStateOf(0)
    var previewMuted by mutableStateOf(true)
    var previewFailed by mutableStateOf(false)

    var jump by mutableStateOf<GuideJump?>(null)
        private set
    private var jumpCount by mutableIntStateOf(0)

    /** The list item a row move is heading for, until its cell or chip reports focus. */
    var pendingRow by mutableStateOf<Int?>(null)

    /** The program whose details are open, if any. */
    var details by mutableStateOf<ProgramDetails?>(null)

    // True once a cell has taken focus; until then the header's buttons hold it, so the request is retried.
    // A guide opened for search gives focus to the search instead.
    var initialFocusDone by mutableStateOf(startSearch)
        private set
    var initialFocusAttempt by mutableIntStateOf(0)

    // Until focus comes down from the header, where the guide was asked to leave it.
    var awaitingEntry by mutableStateOf(startInHeader)
    var returnToSearchChip by mutableStateOf(false)

    /** A channel typed by number that the filter hid; the list moves to its row once All shows it. */
    var numberJump by mutableStateOf<String?>(null)

    // Voice search: the TV's recognizer takes the query and the first matching row takes focus; without a
    // recognizer, or when it is dismissed, the text field takes focus as the Search chip's does.
    var searchFocusRequest by mutableIntStateOf(0)
    var focusResults by mutableStateOf(false)

    // Whether the Back key-down that the guide took is still held, so only its own key-up climbs.
    var backHeld by mutableStateOf(false)

    // The rest of an OK held to open the details goes nowhere, so Watch, focused under it, does not play.
    private val heldOk = HeldOkGuard()
    private var detailsOnRelease: ProgramDetails? = null

    /** The clock moved on: once it leaves the live window, the window and the cursor roll over to the live one. */
    fun rollWindow(now: Instant) {
        val next = GuideTimeline.windowStartFor(liveStart, now)
        if (next != liveStart) {
            liveStartSecond = next.epochSeconds
            dayOffset = 0
            cursor = GuideTimeline.offsetMinutes(next, now)
        }
    }

    // When the app last stopped, on the wall clock, which keeps counting through standby.
    private var leftAt: Instant? = null

    /** The app stopped at [at]: Home, the screen off or the TV in standby. */
    fun left(at: Instant) {
        leftAt = at
    }

    /**
     * The app started again at [at]. True once it was away [REALIGN_AFTER] or longer on the live window: the grid
     * then goes back to now, rather than to listings long over. A day picked with Fast Forward stays.
     */
    fun cameBack(at: Instant): Boolean {
        val away = leftAt?.let { at - it } ?: return false
        leftAt = null
        return away >= REALIGN_AFTER && dayOffset == 0
    }

    /**
     * A cell took focus: the preview shows it, the first focus has landed, and a cursor outside it moves to its start.
     */
    fun cellFocused(channel: Channel, cell: GuideCell) {
        focused = FocusedCell(channel, cell)
        initialFocusDone = true
        lastFocusedGuideId = channel.guideId
        pendingRow = null
        laterFocused = null
        if (cursor !in cell.startMinute until cell.endMinute) cursor = cell.startMinute
    }

    /** A Later line's chip took focus. */
    fun laterFocused(key: String) {
        laterFocused = key
        pendingRow = null
    }

    /** Asks the row for [guideId] to focus its cell at [minute], which the cursor moves to. */
    fun jumpTo(guideId: String, minute: Long) {
        cursor = minute
        jumpCount += 1
        jump = GuideJump(guideId, minute, jumpCount)
    }

    /** The row answered the jump; until the first focus has landed, a miss tries it again. */
    fun jumpHandled(landed: Boolean) {
        jump = null
        if (landed) {
            initialFocusDone = true
        } else if (!initialFocusDone) {
            initialFocusAttempt++
        }
    }

    /**
     * The details of the program in [cell], on the Info key or a held OK, whose rest goes nowhere. Where they open
     * in a window of their own ([inWindow], the bottom sheet), a held OK opens them when it comes up instead: the
     * press has to end on the cell, whose clickable would otherwise keep it and ignore the next OK there.
     */
    fun openDetails(channel: Channel, cell: GuideCell, inWindow: Boolean = false) {
        val opened = ProgramDetails(channel, cell.program)
        if (inWindow && heldOk.held) {
            detailsOnRelease = opened
        } else {
            heldOk.arm()
            details = opened
        }
    }

    /**
     * An OK [type] event that belongs to a held OK: the release of one that opened the details on a cell, or the
     * rest of one held to open them, which goes nowhere. Null when the OK is not held.
     */
    fun heldOkKey(type: KeyEventType): Boolean? {
        val opened = detailsOnRelease
        return when {
            // The cell takes this key-up and ends its press; the details open after it.
            type == KeyEventType.KeyUp && opened != null -> {
                detailsOnRelease = null
                heldOk.onKeyUp()
                details = opened
                false
            }

            // The rest of an OK held to open the details goes nowhere, so Watch, focused under it, does not play.
            type == KeyEventType.KeyDown && heldOk.onKeyDown() -> true

            type == KeyEventType.KeyUp && heldOk.onKeyUp() -> true

            else -> null
        }
    }
}

/**
 * A [GuideState] whose window, cursor, filter, search and last focused channel survive a trip to the player. A
 * fresh guide opens on the live window at [now], with the filter saved as [initialFilterKey].
 */
@Composable
internal fun rememberGuideState(
    now: Instant,
    initialFilterKey: String?,
    startSearch: Boolean,
    startInHeader: Boolean,
): GuideState {
    val liveStartSecond = rememberSaveable { mutableLongStateOf(GuideTimeline.windowStart(now).epochSeconds) }
    val dayOffset = rememberSaveable { mutableIntStateOf(0) }
    val cursor = rememberSaveable {
        val windowStart =
            Instant.fromEpochSeconds(liveStartSecond.longValue) +
                (dayOffset.intValue * GuideTimeline.WINDOW_MINUTES).minutes
        mutableLongStateOf(GuideTimeline.offsetMinutes(windowStart, now))
    }
    val filter = rememberSerializable { mutableStateOf(guideFilterFromKey(initialFilterKey)) }
    val searchText = rememberTextFieldState()
    val searchOpen = rememberSaveable { mutableStateOf(false) }
    val lastFocusedGuideId = rememberSaveable { mutableStateOf<String?>(null) }
    val numbers = rememberChannelNumberInput()
    return remember {
        GuideState(
            liveStartSecond, dayOffset, cursor, filter, searchOpen, lastFocusedGuideId, searchText, startSearch,
            startInHeader, numbers,
        )
    }
}
