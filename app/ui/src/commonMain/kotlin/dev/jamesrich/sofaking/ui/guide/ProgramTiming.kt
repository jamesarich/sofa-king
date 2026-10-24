package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Program
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** How a program stands against the clock, for the detail sheet's "time remaining" line. */
internal sealed interface ProgramTiming {
    /** Airing, with [left] until it ends. */
    data class Airing(val left: Duration) : ProgramTiming

    /** Not started yet, with [until] before it does. */
    data class Upcoming(val until: Duration) : ProgramTiming

    data object Ended : ProgramTiming
}

/** [program]'s timing at [now], in whole minutes rounded up so a program never reads as ending before it does. */
internal fun programTiming(program: Program, now: Instant): ProgramTiming = when {
    now >= program.stop -> ProgramTiming.Ended
    now >= program.start -> ProgramTiming.Airing(wholeMinutesUp(program.stop - now))
    else -> ProgramTiming.Upcoming(wholeMinutesUp(program.start - now))
}

private fun wholeMinutesUp(duration: Duration): Duration {
    val seconds = duration.inWholeSeconds.seconds
    val minutes = seconds.inWholeMinutes.minutes
    return if (minutes < seconds) minutes + 1.minutes else minutes
}
