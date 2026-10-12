package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.player.ChannelNumberEntry

/** Where a typed channel number goes in the guide. */
internal sealed interface GuideNumberTarget {
    /** A row on screen, by its index in the visible list. */
    data class Row(val index: Int) : GuideNumberTarget

    /** A channel in the lineup that the filter hides; the filter gives way to All. */
    data class Hidden(val guideId: String) : GuideNumberTarget

    /** No channel has that number. */
    data object Missing : GuideNumberTarget
}

internal fun guideNumberTarget(entry: ChannelNumberEntry, visible: List<Channel>, all: List<Channel>): GuideNumberTarget {
    entry.indexIn(visible)?.let { return GuideNumberTarget.Row(it) }
    entry.indexIn(all)?.let { return GuideNumberTarget.Hidden(all[it].guideId) }
    return GuideNumberTarget.Missing
}

/** The first list item after a filter change: [focusedId]'s row in the slot the grid keeps it in, else the top. */
internal fun firstItemAfterFilter(visible: List<Channel>, focusedId: String?): Int {
    val row = visible.indexOfFirst { it.guideId == focusedId }
    return if (row < 0) 0 else (row - FOCUS_ROW_SLOT).coerceAtLeast(0)
}
