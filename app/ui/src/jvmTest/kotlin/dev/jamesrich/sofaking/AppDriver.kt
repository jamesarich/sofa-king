package dev.jamesrich.sofaking

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.inDirectory
import dev.jamesrich.sofaking.ui.TV_PROPERTY
import dev.jamesrich.sofaking.ui.player.VlcLivePlayer
import dev.jamesrich.sofaking.ui.player.newDesktopLivePlayer
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.fail

/**
 * Starts the whole app in a 1920 by 1080 test scene, paired with [server] through the link a QR code carries, in the
 * TV layout when [tv] is set, and runs [block] once the guide shows the lineup. libVLC is left out, so the player
 * reports that VLC is missing without looking for it. A failure's screen and semantics tree go to [out].
 */
@OptIn(ExperimentalTestApi::class)
internal fun runSofaKing(tv: Boolean, out: File, server: FakeServer = FakeServer(), block: AppDriver.() -> Unit) {
    out.mkdirs()
    System.setProperty(TV_PROPERTY, tv.toString())
    val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    val backInput = EscapeBackInput()
    try {
        val data = Files.createTempDirectory("sofaking-test").toString()
        initSofaKing(AppStorage.inDirectory(data, ClientInfo("desktop", "test")))
        newDesktopLivePlayer = { VlcLivePlayer(it) { throw UnsatisfiedLinkError("libVLC left out") } }
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            setContent {
                // A window turns an Escape nothing took into a back event; the test scene has no window, so this does.
                val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
                DisposableEffect(dispatcher) {
                    dispatcher?.addInput(backInput)
                    onDispose { dispatcher?.removeInput(backInput) }
                }
                App(pairing = PairingRequest(server.url, "123456"))
            }
            val driver = AppDriver(this, server, backInput, uncaught, out)
            waitUntil("the guide shows the lineup", timeoutMillis = 20_000) { server.guideQueries.get() > 0 && driver.focusedCount() > 0 }
            driver.block()
        }
    } finally {
        stopSofaKing()
        System.clearProperty(TV_PROPERTY)
        newDesktopLivePlayer = { VlcLivePlayer(it) }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        server.stop()
    }
}

/** Presses the remote's keys on the running app and reads what they left focused. */
@OptIn(ExperimentalTestApi::class)
internal class AppDriver(
    val ui: ComposeUiTest,
    val server: FakeServer,
    private val backInput: EscapeBackInput,
    val uncaught: List<Throwable>,
    val out: File,
) {
    /** Presses the named keys in turn ([REMOTE]'s names, as a fuzz trace writes them) and waits for each to settle. */
    fun keys(vararg names: String) = names.forEach { name -> press(REMOTE.first { it.name == name }) }

    fun press(press: Press) {
        send(press)
        ui.waitForIdle()
    }

    /** Waits for the focused node's label to become [label], and fails with the screen saved when it does not. */
    fun assertFocused(label: String?) = assertFocused("\"$label\"") { it == label }

    /** Waits for the focused node's label to match [what], described by [expected], and fails with the screen saved when it does not. */
    fun assertFocused(expected: String, what: (String?) -> Boolean) {
        val reached = runCatching { ui.waitUntil(timeoutMillis = SETTLE_MS) { what(focusedLabel()) } }.isSuccess
        if (!reached) failWith("expected focus on $expected, found \"${focusedLabel()}\"")
    }

    /** Saves the screen and the semantics tree to [out], then fails with [reason]. */
    fun failWith(reason: String, cause: Throwable? = null): Nothing {
        runCatching { ImageIO.write(ui.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(out, "failure.png")) }
        runCatching { File(out, "failure-semantics.txt").writeText(ui.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)) }
        if (cause != null) throw AssertionError(reason, cause) else fail(reason)
    }

    /**
     * The focused node's text, content description, or "field" for a text field, cut to [LABEL_CHARS]; "unlabeled"
     * when it has none of them, null when nothing is focused.
     */
    fun focusedLabel(): String? {
        val node = ui.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull() ?: return null
        val config = node.config
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: config.getOrNull(SemanticsProperties.EditableText)?.let { "field" }
        return text?.take(LABEL_CHARS) ?: "unlabeled"
    }

    fun focusedCount() = ui.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().size

    /**
     * Sends the key the way a window does: through the scene, so an open popup, menu or dialog takes it before the
     * screen behind it, then an Escape or Back nothing took on as a back event.
     */
    @OptIn(InternalComposeUiApi::class)
    private fun send(press: Press) {
        val scene = (ui as SkikoComposeUiTest).scene
        fun dispatch(type: KeyEventType) = ui.runOnUiThread {
            val event = KeyEvent(press.key, type)
            if (scene.sendKeyEvent(event)) backInput.onConsumed(event) else backInput.onKeyEvent(event)
        }
        dispatch(KeyEventType.KeyDown)
        if (press.held) {
            // A held key repeats its key-down, as a keyboard or a remote does, once the repeat delay has passed.
            ui.mainClock.advanceTimeBy(REPEAT_DELAY_MS)
            repeat(((HOLD_MS - REPEAT_DELAY_MS) / REPEAT_MS).toInt()) {
                dispatch(KeyEventType.KeyDown)
                ui.mainClock.advanceTimeBy(REPEAT_MS)
            }
        } else {
            ui.mainClock.advanceTimeBy(TAP_MS)
        }
        dispatch(KeyEventType.KeyUp)
    }

    class Press(val name: String, val key: Key, val weight: Int, val held: Boolean = false)

    companion object {
        const val TAP_MS = 60L
        const val HOLD_MS = 900L
        const val REPEAT_DELAY_MS = 500L
        const val REPEAT_MS = 50L
        const val LABEL_CHARS = 40
        const val SETTLE_MS = 5_000L

        /** The remote's keys, weighted the way people press them: mostly the D-pad, then OK and Back. */
        val REMOTE = listOf(
            Press("Up", Key.DirectionUp, 12),
            Press("Down", Key.DirectionDown, 14),
            Press("Left", Key.DirectionLeft, 10),
            Press("Right", Key.DirectionRight, 12),
            Press("OK", Key.DirectionCenter, 8),
            Press("Enter", Key.Enter, 2),
            Press("HeldOK", Key.DirectionCenter, 1, held = true),
            Press("Back", Key.Back, 6),
            Press("Escape", Key.Escape, 2),
            Press("Menu", Key.Menu, 1),
            Press("Guide", Key.Guide, 1),
            Press("Info", Key.Info, 1),
            Press("ChannelUp", Key.ChannelUp, 2),
            Press("ChannelDown", Key.ChannelDown, 2),
            Press("PlayPause", Key.MediaPlayPause, 1),
            Press("FastForward", Key.MediaFastForward, 1),
            Press("Rewind", Key.MediaRewind, 1),
            Press("Captions", Key.Captions, 1),
            Press("Digit", Key.Five, 1),
        )
    }
}

/**
 * What a window does with a key nothing took: a desktop window turns an Escape key-down into a back event, and an
 * Android activity a Back key-up whose key-down nothing took either.
 */
internal class EscapeBackInput : NavigationEventInput() {
    private var backDown = false

    fun onKeyEvent(event: KeyEvent) {
        when {
            event.key == Key.Escape && event.type == KeyEventType.KeyDown -> dispatchOnBackCompleted()
            event.key == Key.Back && event.type == KeyEventType.KeyDown -> backDown = true
            event.key == Key.Back && event.type == KeyEventType.KeyUp && backDown -> {
                backDown = false
                dispatchOnBackCompleted()
            }
        }
    }

    /** A key-down the app took ends the tracking, as an activity stops tracking a Back the view consumed. */
    fun onConsumed(event: KeyEvent) {
        if (event.key == Key.Back && event.type == KeyEventType.KeyDown) backDown = false
    }
}
