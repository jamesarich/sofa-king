package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.Immutable
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program

/** One item of the guide's list: a channel's row, or the "Later" line under it that search adds. */
@Immutable
internal sealed interface GuideListItem {
    val channel: Channel

    /** Unique in the list, since a channel is listed once per number. */
    val key: String

    data class Row(override val channel: Channel) : GuideListItem {
        override val key: String get() = "${channel.guideId}#${channel.number}"
    }

    /** Upcoming programs of [channel] that match the search, each a jump to its cell. */
    data class Later(override val channel: Channel, val programs: List<Program>) : GuideListItem {
        override val key: String get() = "later:${channel.guideId}#${channel.number}"
    }
}

/** [visible] as list items, with a Later line after every channel in [upcoming] (keyed by guide id). */
internal fun guideListItems(visible: List<Channel>, upcoming: Map<String, List<Program>>): List<GuideListItem> =
    buildList {
        for (channel in visible) {
            add(GuideListItem.Row(channel))
            val later = upcoming[channel.guideId]
            if (!later.isNullOrEmpty()) add(GuideListItem.Later(channel, later))
        }
    }

/** The index of [channel]'s own row in [items], or -1. */
internal fun rowIndexOf(items: List<GuideListItem>, channel: Channel): Int = items.indexOfFirst {
    it is GuideListItem.Row && it.channel.guideId == channel.guideId && it.channel.number == channel.number
}

/** The index of the Later line of [guideId], or -1. */
internal fun laterIndexOf(items: List<GuideListItem>, guideId: String): Int =
    items.indexOfFirst { it is GuideListItem.Later && it.channel.guideId == guideId }
