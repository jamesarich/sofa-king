package dev.jamesrich.sofaking.ui.player

/** One mini-guide row; [pinnedLast] marks the last-watched channel shown above the full list. */
internal data class MiniGuideRow(val channelIndex: Int, val pinnedLast: Boolean) {
    /** Unique per row, since the last-watched channel also appears in its place in the list. */
    val key: String get() = if (pinnedLast) "last:$channelIndex" else "ch:$channelIndex"
}

/**
 * The last-watched channel first when there is one, then every channel in zap order.
 * The list keeps its own copy of that channel so it reads straight through.
 */
internal fun miniGuideRows(channelCount: Int, previousIndex: Int?): List<MiniGuideRow> = buildList {
    val pinned = previousIndex?.takeIf { it in 0 until channelCount }
    if (pinned != null) add(MiniGuideRow(pinned, pinnedLast = true))
    for (index in 0 until channelCount) add(MiniGuideRow(index, pinnedLast = false))
}

/** Position of the current channel's own row, after the pinned row when one is shown. */
internal fun miniGuideFocusRow(channelCount: Int, previousIndex: Int?, currentIndex: Int): Int {
    val pinnedOffset = if (previousIndex != null && previousIndex in 0 until channelCount) 1 else 0
    return pinnedOffset + currentIndex
}
