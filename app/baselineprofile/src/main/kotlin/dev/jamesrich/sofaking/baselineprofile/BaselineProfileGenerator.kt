package dev.jamesrich.sofaking.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Records the classes and methods a cold start, the guide, a channel change, a library title's page and video and a
 * recording's row run, for ahead-of-time compilation.
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
        // A cold start reopens the last channel full screen; Back leaves it for the guide.
        if (device.hasObject(By.res(PLAYER))) {
            device.pressBack()
            check(device.wait(Until.hasObject(By.res(GUIDE)), TIMEOUT_MS)) { "The guide did not open" }
        }

        // Scroll the guide down a page of channels and back, then along the timeline.
        repeat(GUIDE_STEPS) {
            device.pressDPadDown()
            device.waitForIdle()
        }
        repeat(GUIDE_STEPS) {
            device.pressDPadUp()
            device.waitForIdle()
        }
        repeat(TIMELINE_STEPS) {
            device.pressDPadRight()
            device.waitForIdle()
        }
        repeat(TIMELINE_STEPS) {
            device.pressDPadLeft()
            device.waitForIdle()
        }

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

    @Test
    fun libraryItemAndPlayback() = rule.collect(packageName = PACKAGE) {
        launch()
        leavePlayerForGuide()
        // The Library tab, a title's page, then its video: start, seek forward and back, pause and resume.
        if (!openTab(TAB_LIBRARY)) return@collect
        repeat(LIST_STEPS) {
            device.pressDPadDown()
            device.waitForIdle()
        }
        device.pressDPadCenter()
        device.waitForIdle()
        device.pressDPadCenter()
        Thread.sleep(PLAYBACK_MS)
        device.pressDPadRight()
        Thread.sleep(SEEK_MS)
        device.pressDPadLeft()
        Thread.sleep(SEEK_MS)
        device.pressDPadCenter()
        Thread.sleep(SEEK_MS)
        device.pressDPadCenter()
        Thread.sleep(SEEK_MS)
        // Back leaves the player, then the item's page.
        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    @Test
    fun recordingsAndRow() = rule.collect(packageName = PACKAGE) {
        launch()
        leavePlayerForGuide()
        // The DVR tab and one of its rows.
        if (!openTab(TAB_DVR)) return@collect
        repeat(LIST_STEPS) {
            device.pressDPadDown()
            device.waitForIdle()
        }
        device.pressDPadCenter()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    /** A cold start reopens the last channel full screen; Back leaves it for the guide. */
    private fun MacrobenchmarkScope.leavePlayerForGuide() {
        if (device.hasObject(By.res(PLAYER))) {
            device.pressBack()
            check(device.wait(Until.hasObject(By.res(GUIDE)), TIMEOUT_MS)) { "The guide did not open" }
        }
    }

    /** Opens the header tab named [label]; false when the server does not offer it. */
    private fun MacrobenchmarkScope.openTab(label: String): Boolean {
        val tab = device.wait(Until.findObject(By.text(label)), TAB_TIMEOUT_MS) ?: return false
        tab.click()
        device.waitForIdle()
        return true
    }

    /**
     * A cold start up to the first page: the last channel full screen, or the guide. The loader shows until the app has
     * chosen that page, so wait for the page's own test tag, which MainActivity exposes as a resource id.
     */
    private fun MacrobenchmarkScope.launch() {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.res(FIRST_PAGE)), TIMEOUT_MS)) {
            "Neither the guide nor the player opened"
        }
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "dev.jamesrich.sofaking"

        // PLAYER_TEST_TAG and GUIDE_TEST_TAG in the ui module.
        const val PLAYER = "player"
        const val GUIDE = "guide"
        val FIRST_PAGE: Pattern = Pattern.compile("$PLAYER|$GUIDE")
        const val TIMEOUT_MS = 30_000L
        const val PLAYBACK_MS = 5_000L
        const val SEEK_MS = 1_500L
        const val TAB_TIMEOUT_MS = 5_000L
        const val LIST_STEPS = 2

        // nav_library and nav_dvr in the ui module's strings.
        const val TAB_LIBRARY = "Library"
        const val TAB_DVR = "DVR"
        const val GUIDE_STEPS = 12
        const val TIMELINE_STEPS = 6
    }
}
