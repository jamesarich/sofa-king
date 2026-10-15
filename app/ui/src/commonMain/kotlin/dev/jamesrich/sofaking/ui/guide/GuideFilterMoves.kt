package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.player.ChannelNumberEntry

/** All, Favorites, Recent, then every group in the order its first channel appears, so the chips follow the numbering. */
internal fun guideFilters(channels: List<Channel>): List<GuideFilter> =
    listOf(GuideFilter.All, GuideFilter.Favorites, GuideFilter.Recent) + channels.flatMap { it.groups }.distinct().map { GuideFilter.Group(it) }

/** How many channels each filter lists; a channel filed under several groups counts in each, and Recent counts only channels still in the lineup. */
internal fun guideFilterCounts(channels: List<Channel>, favorites: Set<String>, recent: List<String>): Map<GuideFilter, Int> = buildMap {
    put(GuideFilter.All, channels.size)
    put(GuideFilter.Favorites, channels.count { it.guideId in favorites })
    put(GuideFilter.Recent, recentChannels(channels, recent).size)
    for (channel in channels) {
        for (group in channel.groups) {
            val key = GuideFilter.Group(group)
            put(key, (get(key) ?: 0) + 1)
        }
    }
}

/** The channels of [recent] (newest first) that are still in [channels], in that order. */
internal fun recentChannels(channels: List<Channel>, recent: List<String>): List<Channel> {
    val byId = channels.associateBy { it.guideId }
    return recent.mapNotNull { byId[it] }
}

private const val GROUP_KEY = "group:"

/** The filter as the settings store keeps it: null for All, so a fresh install reads as before. */
internal fun GuideFilter.key(): String? = when (this) {
    GuideFilter.All -> null
    GuideFilter.Favorites -> "favorites"
    GuideFilter.Recent -> "recent"
    is GuideFilter.Group -> GROUP_KEY + name
}

/** The filter a stored [key] names; anything unknown, including a group no longer in the lineup, reads as All. */
internal fun guideFilterFromKey(key: String?): GuideFilter = when {
    key == null -> GuideFilter.All
    key == "favorites" -> GuideFilter.Favorites
    key == "recent" -> GuideFilter.Recent
    key.startsWith(GROUP_KEY) && key.length > GROUP_KEY.length -> GuideFilter.Group(key.removePrefix(GROUP_KEY))
    else -> GuideFilter.All
}

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
