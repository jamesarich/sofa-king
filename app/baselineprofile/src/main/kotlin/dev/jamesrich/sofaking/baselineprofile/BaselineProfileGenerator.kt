package dev.jamesrich.sofaking.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods a cold start, the guide and a channel change run, for ahead-of-time compilation.
 * Runs against the app as installed, with its server already set up, so the guide has a lineup to draw.
 *
 * The cold start is recorded on its own with `includeInStartupProfile`: the startup profile decides which classes R8
 * packs into the first dex file, so it must hold only what the launch runs, not the whole journey.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        launch()
    }

    @Test
    fun guideAndZap() = rule.collect(packageName = PACKAGE) {
        launch()

        // Scroll the guide down a page of channels and back, then along the timeline.
        repeat(GUIDE_STEPS) { device.pressDPadDown(); device.waitForIdle() }
        repeat(GUIDE_STEPS) { device.pressDPadUp(); device.waitForIdle() }
        repeat(TIMELINE_STEPS) { device.pressDPadRight(); device.waitForIdle() }
        repeat(TIMELINE_STEPS) { device.pressDPadLeft(); device.waitForIdle() }

        // Tune the focused channel, open it full screen, change channel, then return to the guide.
        device.pressDPadCenter()
        Thread.sleep(PLAYBACK_MS)
        device.pressDPadCenter()
        Thread.sleep(PLAYBACK_MS)
        device.pressDPadUp()
        Thread.sleep(PLAYBACK_MS)
        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    /** A cold start up to the first screen: the last channel full screen, or the guide. */
    private fun MacrobenchmarkScope.launch() {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), TIMEOUT_MS)
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "dev.jamesrich.sofaking"
        const val TIMEOUT_MS = 30_000L
        const val PLAYBACK_MS = 5_000L
        const val GUIDE_STEPS = 12
        const val TIMELINE_STEPS = 6
    }
}
