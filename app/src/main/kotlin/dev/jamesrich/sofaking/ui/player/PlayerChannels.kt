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
