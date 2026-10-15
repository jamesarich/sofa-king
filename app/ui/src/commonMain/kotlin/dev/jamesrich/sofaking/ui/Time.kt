package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.time.TimeSource

private val processStart = TimeSource.Monotonic.markNow()

/** Milliseconds on a monotonic clock that does not jump with the wall clock; the rest timers and pause rules read it. */
internal fun uptimeMs(): Long = processStart.elapsedNow().inWholeMilliseconds

private val clockFormat = LocalTime.Format {
    amPmHour(Padding.NONE)
    char(':')
    minute()
    char(' ')
    amPmMarker("AM", "PM")
}

/** Wall-clock times and days in one [zone], for example "1:30 PM" and "Mon"; the app's strings are US English. */
@Immutable
internal class TimeFormatter(val zone: TimeZone) {
    fun format(instant: Instant): String = instant.toLocalDateTime(zone).time.format(clockFormat)

    fun day(instant: Instant): String = DayOfWeekNames.ENGLISH_ABBREVIATED.names[instant.toLocalDateTime(zone).dayOfWeek.ordinal]

    fun sameDay(a: Instant, b: Instant): Boolean = a.toLocalDateTime(zone).date == b.toLocalDateTime(zone).date

    fun range(start: Instant, stop: Instant): String = "${format(start)} - ${format(stop)}"
}

/** The device zone's formatter; read once per composition, as the zone was before. */
@Composable
internal fun rememberTimeFormatter(): TimeFormatter = remember { TimeFormatter(TimeZone.currentSystemDefault()) }

private val placeholder = Regex("%(\\d+)\\$[sd]")

/**
 * A string resource's template with its `%1$s`/`%2$d` placeholders filled, for text built outside composition
 * (semantics read lazily); in composition, `stringResource(res, args)` does the same.
 */
internal fun formatTemplate(template: String, vararg args: Any): String =
    placeholder.replace(template) { match -> args.getOrNull(match.groupValues[1].toInt() - 1)?.toString() ?: match.value }
