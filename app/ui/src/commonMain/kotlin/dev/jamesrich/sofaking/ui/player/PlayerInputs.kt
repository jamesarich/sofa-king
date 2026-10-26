package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.ProgramFacts
import dev.jamesrich.sofaking.data.RecordingMarks

/** The live player's captions: whether they show, how far ahead of the picture each channel's run, and their look. */
data class PlayerCaptions(
    val enabled: Boolean = false,
    val onEnabledChange: (Boolean) -> Unit = {},
    /** How many milliseconds ahead of the picture a channel's captions run, and the viewer's change to it. */
    val advanceFor: (Channel) -> Int = { AppSettings.DEFAULT_CAPTION_ADVANCE_MS },
    val onAdvanceChange: (Channel, Int) -> Unit = { _, _ -> },
    val style: CaptionStyle = CaptionStyle.SYSTEM,
    val smooth: Boolean = true,
)

/** What the channel list offers: the starred channels in [order], the [recent] ones, and the star's toggle, if any. */
data class ChannelChoices(
    /** Starred channels, for the Favorites chip. */
    val favorites: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
    /** Channels watched lately, latest first, for the Recent chip; empty leaves the chip out. */
    val recent: List<String> = emptyList(),
    /** Stars or unstars a channel; null leaves the star out. */
    val onToggleFavorite: ((Channel) -> Unit)? = null,
)

/** Record on the bar. */
data class PlayerRecording(
    /** The server's scheduled recordings; null where it does not record, which hides Record. */
    val marks: RecordingMarks? = null,
    /** Records the program, or cancels its recording; false when the server did not take it. */
    val onRecord: suspend (Program) -> Boolean = { false },
    /** Plays a recording still being written from its start; null hides Watch from start. */
    val onWatchFromStart: ((recordingId: String) -> Unit)? = null,
)

/** The break screen over the ads a streaming channel marks. */
data class PlayerBreaks(
    /** Whether the break screen covers the ads, and whether their sound plays under it. */
    val mode: LiveBreaks = LiveBreaks.PLAY,
    /** The ads the break screen covered today before now, and where each break screen's time goes when it closes. */
    val dodgedTodayMs: Long = 0,
    val onDodge: (Long) -> Unit = {},
    /** What TMDB says about a program, for the break screen's facts; null when nothing is known. */
    val facts: suspend (Program) -> ProgramFacts? = { null },
)

/** How many info bars have carried the key legend so far, which rides the first [AppSettings.KEY_LEGEND_BARS]. */
data class KeyLegendCount(val shown: Int = AppSettings.KEY_LEGEND_BARS, val onShow: (Int) -> Unit = {})
