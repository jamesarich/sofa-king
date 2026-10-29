package dev.jamesrich.sofaking.ui.recordings

import dev.jamesrich.sofaking.data.RecordingPadding

/**
 * What a rule is changed to: whether it takes new episodes only, how many recordings it keeps, a [padding] when
 * set, and with [anyChannel] no longer limited to one channel.
 */
data class RuleEdit(
    val newOnly: Boolean,
    val keep: Int,
    val padding: RecordingPadding? = null,
    val anyChannel: Boolean = false,
)
