package dev.jamesrich.sofaking.ui.guide

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.isOk
import dev.jamesrich.sofaking.ui.player.ChannelNumberEntry
import dev.jamesrich.sofaking.ui.player.digitValue

/**
 * Typed channel digits, as the player takes them, committed to the row with that number: [focusRow] moves to
 * a [visible] one, a hidden one switches to All first, and a number no channel has shows a notice. Search
 * takes digits as text instead.
 */
internal fun GuideState.commitNumber(
    pending: ChannelNumberEntry,
    visible: List<Channel>,
    all: List<Channel>,
    focusRow: (Channel) -> Unit,
) {
    if (searchOpen) return
    when (val target = guideNumberTarget(pending, visible, all)) {
        is GuideNumberTarget.Row -> focusRow(visible[target.index])

        is GuideNumberTarget.Hidden -> {
            filter = GuideFilter.All
            numberJump = target.guideId
        }

        GuideNumberTarget.Missing -> numbers.missed(pending.digits)
    }
}

/**
 * A key in the grid. Up and Down land on the cell under the time cursor: default focus search treats any cell
 * overlapping a long focused cell (a full-row "No listing") as a candidate and can pick one hours ahead.
 * [focusRow] scrolls item [Int] of [items] into place and focuses it; true when the grid took the key.
 */
internal fun GuideState.onGridKey(
    key: Key,
    type: KeyEventType,
    items: List<GuideListItem>,
    focusRow: (Int) -> Unit,
): Boolean {
    val edgeCell = focused?.cell?.takeIf { laterFocused == null && pendingRow == null }
    if (edgeCell != null && key.stopsAtRowEdge(edgeCell, GuideTimeline.WINDOW_MINUTES)) return true
    val step = key.gridStep() ?: return false
    if (type != KeyEventType.KeyDown) return true
    // Rapid presses arrive before focus lands, so count from the row already requested.
    val current = pendingRow
        ?: laterFocused?.let { later -> items.indexOfFirst { it.key == later }.takeIf { it >= 0 } }
        ?: focused?.channel?.let { c -> rowIndexOf(items, c).takeIf { it >= 0 } }
        ?: -1
    val target = gridTarget(current, step, items.size)
    if (target == null) {
        pendingRow = null
        return false
    }
    if (target != current) focusRow(target)
    return true
}

/**
 * Remote shortcuts answered wherever focus is; everything else goes on to the focused node, and every key
 * press restarts the preview's rest timers. [backAction] is what Back does from where focus is; true when
 * the guide took the key.
 */
internal fun GuideState.onKeyEvent(
    key: Key,
    type: KeyEventType,
    backAction: GuideBack,
    controls: GuideControls,
): Boolean = backKey(key, type, backAction, controls)
    ?: okKey(key, type)
    ?: (type == KeyEventType.KeyDown && shortcutKey(key, controls))

/**
 * Back climbs here rather than in BackHandler: Compose turns an unhandled Back key-down into a focus exit, and
 * where focus sits on a node with a focusable parent that exit succeeds and eats the press. The open details take
 * their own Back. Null when the key goes on.
 */
private fun GuideState.backKey(key: Key, type: KeyEventType, backAction: GuideBack, controls: GuideControls): Boolean? =
    when {
        key != Key.Back || details != null -> null

        type == KeyEventType.KeyDown && backAction != GuideBack.EXIT -> {
            backHeld = true
            true
        }

        type == KeyEventType.KeyUp && backHeld -> {
            backHeld = false
            controls.climbBack()
            true
        }

        else -> null
    }

/** OK: opens the details held for its release, or commits typed digits on key-up; null when the key goes on. */
private fun GuideState.okKey(key: Key, type: KeyEventType): Boolean? {
    if (!key.isOk) return null
    return heldOkKey(type) ?: when {
        // OK with digits typed commits them on key-up, as the player does; the cell does not play on this press.
        numbers.hasDigits && !searchOpen -> {
            if (type == KeyEventType.KeyUp) numbers.commit()
            true
        }

        else -> null
    }
}

/** A key-down that Back and OK left: the guide's shortcuts and the digits of a channel number. */
private fun GuideState.shortcutKey(key: Key, controls: GuideControls): Boolean {
    controls.restartRest()
    when (val shortcut = key.guideShortcut()) {
        GuideShortcut.NOW -> {
            details = null
            controls.now()
        }

        GuideShortcut.SEARCH -> {
            details = null
            controls.search()
        }

        GuideShortcut.DETAILS -> {
            // Only from a cell: the one kept for the preview after focus left may not be on screen to return to.
            if (details != null) {
                controls.closeDetails()
            } else {
                focused?.takeIf { gridFocused }?.let { openDetails(it.channel, it.cell) }
            }
        }

        GuideShortcut.DAY_FORWARD, GuideShortcut.DAY_BACK -> {
            // Closed the way Back closes them, so focus returns to the cell even where there is no day to move to.
            if (details != null) controls.closeDetails()
            if (!searchOpen) controls.dayJump(shortcut)
        }

        null -> {
            val digit = key.digitValue()
            // A number typed over the details would tune from under them.
            if (digit == null || searchOpen || details != null) return false
            numbers.append(digit)
        }
    }
    return true
}
