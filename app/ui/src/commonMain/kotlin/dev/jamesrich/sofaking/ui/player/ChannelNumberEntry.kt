package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.formatNumber
import kotlinx.coroutines.delay

/** How long typed digits wait for another before they commit, the same in the player and the guide. */
internal const val DIGIT_COMMIT_MS = 1_500L

/** How long "No channel N" shows. */
internal const val NUMBER_MISS_MS = 2_000L

/** Digits typed on the remote, matched against the zap list by displayed channel number. */
internal data class ChannelNumberEntry(val digits: String = "") {

    fun append(digit: Int): ChannelNumberEntry {
        require(digit in DIGITS) { "digit out of range: $digit" }
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

/**
 * The channel digits being typed and the miss notice for them, shared by the player and the guide. Digits
 * commit through [onCommit] on OK or [DIGIT_COMMIT_MS] after the last one; a number no channel has shows as
 * [miss] for [NUMBER_MISS_MS]. The screen sets [onCommit] once its handler exists.
 */
@Stable
internal class ChannelNumberInput {
    var entry: ChannelNumberEntry by mutableStateOf(ChannelNumberEntry())
        private set

    var miss: String? by mutableStateOf<String?>(null)
        private set

    /** Counts misses, so the same number missed twice restarts the notice's timer. */
    var misses: Int by mutableIntStateOf(0)
        private set

    var onCommit: (ChannelNumberEntry) -> Unit = {}

    val hasDigits: Boolean get() = entry.digits.isNotEmpty()

    fun append(digit: Int) {
        entry = entry.append(digit)
    }

    fun clear() {
        entry = ChannelNumberEntry()
    }

    /** Hands the typed digits to [onCommit], cleared first, so the handler may report a miss or start over. */
    fun commit() {
        val pending = entry.takeIf { it.digits.isNotEmpty() } ?: return
        entry = ChannelNumberEntry()
        onCommit(pending)
    }

    fun missed(digits: String) {
        miss = digits
        misses++
    }

    fun clearMiss() {
        miss = null
    }
}

/** The screen's number input with its commit and notice timers. */
@Composable
internal fun rememberChannelNumberInput(): ChannelNumberInput {
    val input = remember { ChannelNumberInput() }
    LaunchedEffect(input.entry) {
        if (!input.hasDigits) return@LaunchedEffect
        delay(DIGIT_COMMIT_MS)
        input.commit()
    }
    LaunchedEffect(input.misses) {
        if (input.miss == null) return@LaunchedEffect
        delay(NUMBER_MISS_MS)
        input.clearMiss()
    }
    return input
}

private val DIGITS = 0..9
