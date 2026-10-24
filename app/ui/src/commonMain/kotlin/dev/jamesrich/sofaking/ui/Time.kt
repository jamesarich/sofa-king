package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.jamesrich.sofaking.data.ClockFormat
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

private val processStart = TimeSource.Monotonic.markNow()

/**
 * Milliseconds on a monotonic clock that does not jump with the wall clock; the rest timers and pause rules read it.
 */
internal fun uptimeMs(): Long = processStart.elapsedNow().inWholeMilliseconds

/** The clock a scrub rests by: [uptimeMs], or in a test the test's own clock, so a slow machine does not settle it. */
internal val LocalUptimeMs = staticCompositionLocalOf<() -> Long> { ::uptimeMs }

private val twelveHourFormat = LocalTime.Format {
    amPmHour(Padding.NONE)
    char(':')
    minute()
    char(' ')
    amPmMarker("AM", "PM")
}

private val twentyFourHourFormat = LocalTime.Format {
    hour()
    char(':')
    minute()
}

/**
 * Wall-clock times and days in one [zone], for example "1:30 PM" (or "13:30" with [twentyFourHour]) and "Mon";
 * the app's strings are US English.
 */
@Immutable
internal class TimeFormatter(val zone: TimeZone, val twentyFourHour: Boolean = false) {
    private val clockFormat = if (twentyFourHour) twentyFourHourFormat else twelveHourFormat

    fun format(instant: Instant): String = instant.toLocalDateTime(zone).time.format(clockFormat)

    fun day(instant: Instant): String =
        DayOfWeekNames.ENGLISH_ABBREVIATED.names[instant.toLocalDateTime(zone).dayOfWeek.ordinal]

    fun sameDay(a: Instant, b: Instant): Boolean = a.toLocalDateTime(zone).date == b.toLocalDateTime(zone).date

    /** Calendar days from [from] to [to] in the zone: 0 on the same day, 1 when [to] is tomorrow. */
    fun daysBetween(from: Instant, to: Instant): Int =
        from.toLocalDateTime(zone).date.daysUntil(to.toLocalDateTime(zone).date)

    fun range(start: Instant, stop: Instant): String = "${format(start)} - ${format(stop)}"
}

/** The clock format the screens write times in; the app provides the setting, and the default follows the platform. */
internal val LocalClockFormat = staticCompositionLocalOf { ClockFormat.AUTO }

/**
 * The zone times show in: the device's, which the app provides from [rememberSystemTimeZone] so it follows a change,
 * or a fixed one in previews and screenshot tests.
 */
internal val LocalTimeZone = staticCompositionLocalOf { TimeZone.currentSystemDefault() }

/** The [LocalTimeZone]'s formatter in the [LocalClockFormat], made again when either changes. */
@Composable
internal fun rememberTimeFormatter(): TimeFormatter {
    val zone = LocalTimeZone.current
    val format = LocalClockFormat.current
    val systemTwentyFourHour = rememberSystem24HourClock()
    val twentyFourHour = when (format) {
        ClockFormat.AUTO -> systemTwentyFourHour
        ClockFormat.TWELVE_HOUR -> false
        ClockFormat.TWENTY_FOUR_HOUR -> true
    }
    return remember(twentyFourHour, zone) { TimeFormatter(zone, twentyFourHour) }
}

private val placeholder = Regex("%(\\d+)\\$[sd]")

/**
 * A string resource's template with its `%1$s`/`%2$d` placeholders filled, for text built outside composition
 * (semantics read lazily); in composition, `stringResource(res, args)` does the same.
 */
internal fun formatTemplate(template: String, vararg args: Any): String = placeholder.replace(template) { match ->
    args.getOrNull(match.groupValues[1].toInt() - 1)?.toString() ?: match.value
}

/** A time in the item as `M:SS` or `H:MM:SS`. */
internal fun formatPlayTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
}

/** A duration in whole minutes as "2 h 5 min", "45 min" or "1 h": a program's time left, a library item's length. */
internal fun Duration.hoursAndMinutes(): String {
    val hours = inWholeHours
    val minutes = (inWholeMinutes - hours * 60).toInt()
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
        else -> "$minutes min"
    }
}
