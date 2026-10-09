package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.formatNumber

/** Digits typed on the remote, matched against the zap list by displayed channel number. */
internal data class ChannelNumberEntry(val digits: String = "") {

    fun append(digit: Int): ChannelNumberEntry {
        require(digit in 0..9) { "digit out of range: $digit" }
        return if (digits.length >= MAX_DIGITS) this else copy(digits = digits + digit)
    }

    /** Index of the first channel whose displayed number equals the buffer, or null. */
    fun indexIn(channels: List<Channel>): Int? {
        if (digits.isEmpty()) return null
        return channels.indexOfFirst { it.number.formatNumber() == digits }.takeIf { it >= 0 }
    }

    companion object {
        const val MAX_DIGITS = 5
    }
}
