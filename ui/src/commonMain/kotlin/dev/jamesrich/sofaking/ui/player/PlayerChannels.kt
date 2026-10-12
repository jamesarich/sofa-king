package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel

/**
 * Row of [channel] in a refreshed [channels] list, which may have been reordered or grown since it was
 * tuned. The same guide id and number wins, then the same guide id; null when the channel is gone.
 */
internal fun indexAfterRefresh(channels: List<Channel>, channel: Channel): Int? {
    val exact = channels.indexOfFirst { it.guideId == channel.guideId && it.number == channel.number }
    if (exact >= 0) return exact
    return channels.indexOfFirst { it.guideId == channel.guideId }.takeIf { it >= 0 }
}

/**
 * Row of the channel that replaced [channel] after the server gave its lineup new ids: the same number under a
 * different id, else the same name (ignoring case). Null when nothing stands in for it, including when only
 * the dead id itself is listed, since the server has just refused to play it.
 */
internal fun resolveReplacement(channels: List<Channel>, channel: Channel): Int? {
    val byNumber = channels.indexOfFirst { it.guideId != channel.guideId && it.number == channel.number }
    if (byNumber >= 0) return byNumber
    val name = channel.name.trim()
    return channels.indexOfFirst { it.guideId != channel.guideId && it.name.trim().equals(name, ignoreCase = true) }.takeIf { it >= 0 }
}
