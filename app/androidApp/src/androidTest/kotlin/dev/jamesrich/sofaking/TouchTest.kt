package dev.jamesrich.sofaking

import android.content.Intent
import android.graphics.Point
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * The phone app driven by touch in an emulator, against the fixture server (`app/scripts/fixture_server.py`), whose
 * address as the emulator reaches it and token arrive as the instrumentation arguments `sofakingServer` and
 * `sofakingToken`; without them the tests skip. Each test pairs the app through a `sofaking://pair` link, as from the
 * admin portal's QR code, and the server's admin API is the check on what the app did. These cover what only Android
 * does: picture-in-picture, the predictive back gesture and rotation.
 */
@RunWith(AndroidJUnit4::class)
class TouchTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var server: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchPaired() {
        val address = arguments.getString("sofakingServer")
        val token = arguments.getString("sofakingToken")
        assumeTrue("Needs the fixture server: pass sofakingServer and sofakingToken", address != null && token != null)
        server = FixtureServer(address!!, token!!)
        // The back gesture from the screen's edge, which three-button navigation does not have.
        device.executeShellCommand("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
        device.setOrientationNatural()
        val link = Uri.parse("sofaking://pair").buildUpon()
            .appendQueryParameter("server", address).appendQueryParameter("code", server.pairingCode()).build()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch(Intent(Intent.ACTION_VIEW, link).setClass(context, MainActivity::class.java))
        // A start reopens the last channel full screen; Back leaves it for the guide.
        check(device.wait(Until.hasObject(By.res(FIRST_PAGE)), TIMEOUT_MS)) { "Neither the guide nor the player opened" }
        if (device.hasObject(By.res(PLAYER))) device.pressBack()
        check(device.wait(Until.hasObject(By.res(GUIDE)), TIMEOUT_MS)) { "The guide did not open" }
    }

    @After
    fun closeApp() {
        if (!::scenario.isInitialized) return
        device.setOrientationNatural()
        // Closed, the activity stops, and the app releases what it played; a walk can have left a second one open.
        scenario.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activities = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.entries.filter { it != Stage.DESTROYED }.flatMap { activities.getActivitiesInStage(it) }.forEach { it.finish() }
        }
        eventually("the app to release its sessions once closed") { server.sessions().isEmpty() }
    }

    /** Home from the player shrinks it into picture-in-picture, still playing; the launcher brings it back full screen. */
    @Test
    fun pictureInPicture() {
        play("Sofa Sports")
        device.pressHome()
        eventually("picture-in-picture") { inPictureInPicture() }
        Thread.sleep(PLAYBACK_MS)
        check(server.watching().isNotEmpty()) { "The player stopped in picture-in-picture" }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        eventually("the player back full screen") { !inPictureInPicture() && device.hasObject(By.res(PLAYER)) }
        check(server.watching().size == 1) { "Back from picture-in-picture, the app holds ${server.watching().size} sessions" }
        device.pressBack()
        eventually("the guide, and the channel closed") { device.hasObject(By.res(GUIDE)) && server.watching().isEmpty() }
    }

    /** A back swipe let go at the edge leaves the player as it was; one carried through returns to the guide. */
    @Test
    fun predictiveBack() {
        play("Sofa Sports")
        val middle = device.displayHeight / 2
        val edge = Point(1, middle)
        device.swipe(arrayOf(edge, Point(device.displayWidth / 3, middle), edge), SWIPE_STEPS)
        Thread.sleep(SETTLE_MS)
        check(device.hasObject(By.res(PLAYER)) && server.watching().isNotEmpty()) { "A cancelled back swipe left the player" }

        device.swipe(edge.x, middle, device.displayWidth / 2, middle, SWIPE_STEPS)
        eventually("a back swipe to return to the guide") { device.hasObject(By.res(GUIDE)) }
        eventually("the channel to close") { server.watching().isEmpty() }
    }

    /** Turning the phone while a channel plays keeps the one session, and the player on screen. */
    @Test
    fun rotation() {
        play("Sofa Sports")
        listOf({ device.setOrientationLeft() }, { device.setOrientationNatural() }, { device.setOrientationRight() }, { device.setOrientationNatural() })
            .forEach { turn ->
                turn()
                Thread.sleep(SETTLE_MS)
                check(device.hasObject(By.res(PLAYER))) { "The player left the screen when the phone turned" }
            }
        check(server.watching().size == 1) { "After turning, the app holds ${server.watching().size} sessions" }
    }

    /**
     * Random taps, swipes, back swipes, long presses, turns and trips to the Home screen, seeded so a walk replays from
     * the `sofakingWalkSeed` argument (and `sofakingWalkSteps`). A crash fails the run; the app must release what it
     * played once it closes (see [closeApp]).
     */
    @Test
    fun walkWithRandomTouches() {
        val seed = arguments.getString("sofakingWalkSeed")?.toLong() ?: System.currentTimeMillis()
        val steps = arguments.getString("sofakingWalkSteps")?.toInt() ?: WALK_STEPS
        val random = Random(seed)
        val walked = mutableListOf<String>()
        repeat(steps) {
            val touch = TOUCHES.random(random)
            walked += touch.name
            touch.act(device, random)
            device.waitForIdle(IDLE_MS)
        }
        println("Walk: seed $seed, $steps touches: ${walked.joinToString(" ")}")
        // Back in front, so the close checks what the walk left open.
        if (!device.hasObject(By.pkg(PACKAGE).depth(0))) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), TIMEOUT_MS)
        }
    }

    // A tap on what a channel airs now plays it full screen; the channel's name is not a button.
    private fun play(channel: String) {
        val now = By.desc(Pattern.compile(Pattern.quote("$channel, ") + ".*airing now.*"))
        val cell = checkNotNull(device.wait(Until.findObject(now), TIMEOUT_MS)) { "No $channel airing now in the guide" }
        cell.click()
        check(device.wait(Until.hasObject(By.res(PLAYER)), TIMEOUT_MS)) { "A tap on $channel did not open the player" }
        eventually("$channel to play") { server.watching().isNotEmpty() }
    }

    private fun inPictureInPicture(): Boolean {
        var pinned = false
        scenario.onActivity { pinned = it.isInPictureInPictureMode }
        return pinned
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private class Touch(val name: String, val weight: Int, val act: (UiDevice, Random) -> Unit)

    private companion object {
        const val PACKAGE = "dev.jamesrich.sofaking"

        // PLAYER_TEST_TAG and GUIDE_TEST_TAG in the ui module, which MainActivity exposes as resource ids.
        const val PLAYER = "player"
        const val GUIDE = "guide"
        val FIRST_PAGE: Pattern = Pattern.compile("$PLAYER|$GUIDE")

        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 500L
        const val PLAYBACK_MS = 5_000L
        const val SETTLE_MS = 2_000L
        const val IDLE_MS = 1_000L
        const val SWIPE_STEPS = 30
        const val WALK_STEPS = 150

        private fun UiDevice.point(random: Random) =
            Point(displayWidth * (3 + random.nextInt(94)) / 100, displayHeight * (3 + random.nextInt(94)) / 100)

        private val WEIGHTED = listOf(
            Touch("tap", 12) { device, random -> device.point(random).let { device.click(it.x, it.y) } },
            Touch("swipe", 8) { device, random ->
                val from = device.point(random)
                val to = device.point(random)
                device.swipe(from.x, from.y, to.x, to.y, 10)
            },
            Touch("back", 4) { device, _ ->
                device.swipe(1, device.displayHeight / 2, device.displayWidth / 2, device.displayHeight / 2, SWIPE_STEPS)
            },
            Touch("longPress", 1) { device, random -> device.point(random).let { device.swipe(it.x, it.y, it.x, it.y, 200) } },
            Touch("turn", 1) { device, random -> if (random.nextBoolean()) device.setOrientationLeft() else device.setOrientationNatural() },
            Touch("home", 1) { device, _ ->
                device.pressHome()
                device.waitForIdle(IDLE_MS)
                device.executeShellCommand("monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1")
            },
        )

        /** Each touch as often as its weight says. */
        val TOUCHES: List<Touch> = WEIGHTED.flatMap { touch -> List(touch.weight) { touch } }
    }
}

/** The fixture server's admin API, as the tests check what the app did. */
private class FixtureServer(private val base: String, private val token: String) {
    /** The app's open playback sessions; the server's own (recordings) are external. */
    fun sessions(): List<JSONObject> {
        val all = JSONArray(call("/api/v1/admin/sessions"))
        return List(all.length()) { all.getJSONObject(it) }.filter { !it.optBoolean("external") }
    }

    /** The sessions a channel opened full screen, rather than a preview in the guide. */
    fun watching(): List<JSONObject> = sessions().filter { it.optString("intent") == "watch" }

    fun pairingCode(): String = JSONObject(call("/api/v1/admin/pairing-codes", "POST")).getString("code")

    private fun call(path: String, method: String = "GET"): String {
        val connection = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
