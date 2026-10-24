package dev.jamesrich.sofaking

import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jamesrich.sofaking.ui.LocalTimeZone
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.guide.GuideTimeline
import dev.jamesrich.sofaking.ui.rememberSystemTimeZone
import kotlinx.datetime.TimeZone
import java.io.File
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The guide writes times in the device's zone as it is now, not as it was when something first asked: an Android
 * TV emulator gets its zone from the network a moment after boot, and a zone read once kept UTC for the life of the
 * app. The device's zone here is the JVM's default, which kotlinx-datetime reads.
 */
@OptIn(ExperimentalTestApi::class)
class AppTimeZoneTest {
    @Test
    fun `the guide writes the device's zone when it changed after the first read`() = withDefaultZone(UTC) {
        // Something reads the zone while the device is still on UTC.
        runSkikoComposeUiTest(Size(400f, 100f)) {
            setContent { Text(LocalTimeZone.current.id) }
            onNodeWithText(UTC).assertExists()
        }
        setDefaultZone(CHICAGO)
        runSofaKing(tv = true, File("build/journeys", "time-zone")) {
            assertFocused("the airing program's times in Chicago, not UTC") { label ->
                label != null && airingRanges(CHICAGO).any { it in label } && airingRanges(UTC).none { it in label }
            }
        }
    }

    @Test
    fun `the app's zone follows a change on the next wall-clock minute`() = withDefaultZone(UTC) {
        runSkikoComposeUiTest(Size(400f, 100f)) {
            setContent { Text(rememberSystemTimeZone(LocalLifecycleOwner.current.lifecycle).value.id) }
            onNodeWithText(UTC).assertExists()
            setDefaultZone(KOLKATA)
            mainClock.advanceTimeBy(1.minutes.inWholeMilliseconds + 1)
            onNodeWithText(KOLKATA).assertExists()
        }
    }

    /** The half hour on now in [zone], in both clock formats, for each slot a slow run could have crossed into. */
    private fun airingRanges(zone: String): List<String> {
        val now = Clock.System.now()
        return listOf(now - 1.minutes, now).flatMap { at ->
            val start = halfHourOf(at)
            listOf(false, true).map { TimeFormatter(TimeZone.of(zone), it).range(start, start + 30.minutes) }
        }
    }

    private fun halfHourOf(at: Instant): Instant =
        Instant.fromEpochSeconds(at.epochSeconds - at.epochSeconds.mod(GuideTimeline.SLOT_MINUTES * 60))

    private fun setDefaultZone(id: String) = java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))

    private fun withDefaultZone(id: String, block: () -> Unit) {
        val saved = java.util.TimeZone.getDefault()
        try {
            setDefaultZone(id)
            block()
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    private companion object {
        const val UTC = "UTC"
        const val CHICAGO = "America/Chicago"
        const val KOLKATA = "Asia/Kolkata"
    }
}
