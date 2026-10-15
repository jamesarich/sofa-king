package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Programme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How a programme stands against the clock, for the detail sheet's "time remaining" line. */
internal sealed interface ProgrammeTiming {
    /** Airing, with [left] until it ends. */
    data class Airing(val left: Duration) : ProgrammeTiming

    /** Not started yet, with [until] before it does. */
    data class Upcoming(val until: Duration) : ProgrammeTiming

    data object Ended : ProgrammeTiming
}

/** [programme]'s timing at [now], in whole minutes rounded up so a programme never reads as ending before it does. */
internal fun programmeTiming(programme: Programme, now: Instant): ProgrammeTiming = when {
    now >= programme.stop -> ProgrammeTiming.Ended
    now >= programme.start -> ProgrammeTiming.Airing(wholeMinutesUp(programme.stop - now))
    else -> ProgrammeTiming.Upcoming(wholeMinutesUp(programme.start - now))
}

private fun wholeMinutesUp(duration: Duration): Duration {
    val seconds = duration.inWholeSeconds
    return ((seconds + 59) / 60).minutes
}

/** A duration as "2 h 5 min", "45 min" or "1 h", for the sheet. */
internal fun Duration.hoursAndMinutes(): String {
    val hours = inWholeHours
    val minutes = (inWholeMinutes - hours * 60).toInt()
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
        else -> "$minutes min"
    }
}
