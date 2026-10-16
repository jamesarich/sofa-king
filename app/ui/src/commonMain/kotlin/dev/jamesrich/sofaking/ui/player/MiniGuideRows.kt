package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.orderedFavorites

/** The channel list's filter chips: the channels watched lately, the starred ones, the guide filter the player was opened from, or every channel. */
internal enum class MiniGuideFilter { RECENT, FAVORITES, SCOPE, ALL }

/** The chips the panel shows: Recent only once something was watched, Scope only when the player was opened from a guide filter. */
internal fun miniGuideFilters(hasRecent: Boolean, scoped: Boolean): List<MiniGuideFilter> = buildList {
    if (hasRecent) add(MiniGuideFilter.RECENT)
    add(MiniGuideFilter.FAVORITES)
    if (scoped) add(MiniGuideFilter.SCOPE)
    add(MiniGuideFilter.ALL)
}

/** The chip selected when the panel opens: the guide filter the channel came from, else every channel. */
internal fun miniGuideDefaultFilter(scoped: Boolean): MiniGuideFilter = if (scoped) MiniGuideFilter.SCOPE else MiniGuideFilter.ALL

/** One mini-guide row; [pinnedLast] marks the last-watched channel shown above the full list. */
internal data class MiniGuideRow(val channel: Channel, val pinnedLast: Boolean) {
    /** Unique per row, since the last-watched channel also appears in its place in the list. */
    val key: String get() = if (pinnedLast) "last:${channel.guideId}" else "ch:${channel.guideId}"
}

/**
 * The rows for [filter]: the last-watched channel first on the two zap-order lists when there is one, then the
 * channels in lineup order, or in the order they were watched for Recent. The list keeps its own copy of the
 * last channel so it reads straight through.
 */
internal fun miniGuideRows(
    filter: MiniGuideFilter,
    lineup: List<Channel>,
    scopeIds: Set<String>?,
    favorites: Set<String>,
    recent: List<String>,
    previousGuideId: String?,
    favoritesOrder: List<String> = emptyList(),
): List<MiniGuideRow> {
    val channels = when (filter) {
        MiniGuideFilter.RECENT -> recent.mapNotNull { id -> lineup.firstOrNull { it.guideId == id } }
        MiniGuideFilter.FAVORITES -> orderedFavorites(lineup, favorites, favoritesOrder)
        MiniGuideFilter.SCOPE -> if (scopeIds == null) lineup else lineup.filter { it.guideId in scopeIds }
        MiniGuideFilter.ALL -> lineup
    }
    val pinned = previousGuideId
        ?.takeIf { filter == MiniGuideFilter.ALL || filter == MiniGuideFilter.SCOPE }
        ?.let { id -> channels.firstOrNull { it.guideId == id } }
    return buildList {
        if (pinned != null) add(MiniGuideRow(pinned, pinnedLast = true))
        channels.mapTo(this) { MiniGuideRow(it, pinnedLast = false) }
    }
}

/** Position of the current channel's own row, after the pinned row when one is shown, else the top of the list. */
internal fun miniGuideFocusRow(rows: List<MiniGuideRow>, currentGuideId: String?): Int =
    rows.indexOfFirst { !it.pinnedLast && it.channel.guideId == currentGuideId }.coerceAtLeast(0)
