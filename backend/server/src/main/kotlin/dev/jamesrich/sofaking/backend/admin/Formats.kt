package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.GIB
import dev.jamesrich.sofaking.backend.KIB
import dev.jamesrich.sofaking.backend.MIB
import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import kotlinx.html.A
import kotlinx.html.a
import kotlinx.html.math
import kotlinx.html.s
import kotlinx.html.time
import java.time.Duration
import java.time.Instant

/** A running time as "3 d 4 h", "5 h 12 min" or "7 min". */
internal fun uptime(from: Instant, now: Instant): String {
    val running = Duration.between(from, now).coerceAtLeast(Duration.ZERO)
    return when {
        running.toDays() > 0 -> "${running.toDays()} d ${running.toHoursPart()} h"
        running.toHours() > 0 -> "${running.toHours()} h ${running.toMinutesPart()} min"
        else -> "${running.toMinutes()} min"
    }
}

internal fun relative(instant: Instant, now: Instant): String {
    val seconds = Duration.between(instant, now).seconds
    val future = seconds < 0
    val s = kotlin.math.abs(seconds)
    val text = when {
        s < 60 -> return if (future) "in under a minute" else "just now"
        s < 3600 -> "${s / 60} min"
        s < 86_400 -> "${s / 3600} h"
        else -> "${s / 86_400} d"
    }
    return if (future) "in $text" else "$text ago"
}

internal fun thousands(n: Int): String = "%,d".format(java.util.Locale.US, n)

internal fun plural(n: Int, one: String, many: String = one + "s"): String =
    "${thousands(n)} ${if (n == 1) one else many}"

/** A running time as "12 min" or "2 h 5 min"; [startedAt] is an instant. */
internal fun since(startedAt: String, now: Instant): String {
    val from = runCatching { Instant.parse(startedAt) }.getOrNull() ?: return ""
    return hoursAndMinutes(Duration.between(from, now).coerceAtLeast(Duration.ZERO))
}

/** A length of time as "3 h 12 min", "7 min" or "40 s". */
internal fun hoursAndMinutes(duration: Duration): String = when {
    duration.toHours() > 0 -> "${duration.toHours()} h ${duration.toMinutesPart()} min"
    duration.toMinutes() > 0 -> "${duration.toMinutes()} min"
    else -> "${duration.seconds} s"
}

/** A byte count as "1.2 GB", "340 MB", "12 KB" or "0 B". */
internal fun bytesText(bytes: Long): String = when {
    bytes >= GIB -> "%.1f GB".format(java.util.Locale.US, bytes.toDouble() / GIB)
    bytes >= MIB -> "${bytes / MIB} MB"
    bytes >= KIB -> "${bytes / KIB} KB"
    else -> "$bytes B"
}

/** A duration as "12 min", "6 h" or "1.5 s". */
internal fun period(duration: Duration): String {
    val wholeHours = duration.toMinutesPart() == 0 && duration.toSecondsPart() == 0
    return when {
        duration.toDays() > 0 && duration.toHoursPart() == 0 && wholeHours -> "${duration.toDays()} d"
        duration.toHours() > 0 && wholeHours -> "${duration.toHours()} h"
        duration.toMinutes() > 0 -> "${duration.toMinutes()} min"
        else -> "${duration.seconds} s"
    }
}

internal fun took(ms: Long): String {
    val duration = Duration.ofMillis(ms)
    return when {
        duration.toMinutes() > 0 -> "${duration.toMinutes()} min ${duration.toSecondsPart()} s"
        duration.seconds > 0 -> "%.1f s".format(java.util.Locale.US, ms.toDouble() / MS_PER_SECOND)
        else -> "$ms ms"
    }
}

/** A length of time as "3 h 12 min", "7 min" or "40 s". */
internal fun spoken(ms: Long): String = hoursAndMinutes(Duration.ofMillis(ms))
