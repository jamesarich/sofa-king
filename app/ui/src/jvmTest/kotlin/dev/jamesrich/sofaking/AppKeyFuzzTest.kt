package dev.jamesrich.sofaking

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.inDirectory
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.Timer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.concurrent.schedule
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Drives the whole app with random remote keys against a fake server and fails when a key throws, focus is lost and
 * does not come back, or the app holds more than one playback session. It pairs through the same link a QR code
 * carries, so the shell, the tabs, the guide, the player and Settings run as they do on a desktop.
 *
 * `SOFAKING_FUZZ_SEED` and `SOFAKING_FUZZ_STEPS` replay a failure's keys or run a longer soak, and `SOFAKING_FUZZ_KEYS`
 * presses a fixed list of keys by name instead. The ViewModels run on the Swing thread in real time beside the test
 * (the test's UI thread needs Main, so it cannot be swapped for a test dispatcher), so a seed replays the same keys,
 * not always the same timing. Each run writes a trace of the keys and what they left focused to
 * build/fuzz; a failure adds the screen and its semantics tree.
 */
@OptIn(ExperimentalTestApi::class)
class AppKeyFuzzTest {
    private val seed = System.getenv("SOFAKING_FUZZ_SEED")?.toLongOrNull() ?: 1L
    private val script = System.getenv("SOFAKING_FUZZ_KEYS")?.split(',')?.map { name -> PRESSES.first { it.name == name.trim() } }
    private val steps = script?.size ?: System.getenv("SOFAKING_FUZZ_STEPS")?.toIntOrNull() ?: 300

    /** What each key left focused, by its label, so a run that sits in one corner of the app shows it. */
    private val visited = mutableMapOf<String, Int>()
    private val trace = mutableListOf<String>()
    private val backInput = EscapeBackInput()

    @Test
    fun `random remote keys never crash the app, lose focus or leak a session`() {
        val server = FakeServer()
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        val watchdog = hangWatchdog()
        try {
            val data = Files.createTempDirectory("sofaking-fuzz").toString()
            initSofaKing(AppStorage.inDirectory(data, ClientInfo("desktop", "fuzz")))
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
                waitUntil("the guide shows the lineup", timeoutMillis = 20_000) { server.guideQueries.get() > 0 && focusedCount() > 0 }
                val started = System.nanoTime()
                walk(server, uncaught)
                println("Key fuzz: seed $seed, $steps keys in ${(System.nanoTime() - started) / 1_000_000_000} s, ${server.sessionsOpened.get()} sessions opened, ${server.guideQueries.get()} guide queries, ${visited.size} focus targets")
                File("build/fuzz").apply { mkdirs() }.resolve("visited-$seed.txt")
                    .writeText(visited.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.value}\t${it.key}" })
                File("build/fuzz/trace-$seed.txt").writeText(trace.joinToString("\n"))
            }
        } finally {
            watchdog.cancel()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            server.stop()
        }
    }

    /**
     * A run stuck past [HANG_MS] leaves every thread's stack and the keys so far in build/fuzz, which CI uploads,
     * since a hung test otherwise reports nothing before the job's own timeout cancels it.
     */
    private fun hangWatchdog() = Timer("fuzz-watchdog", true).apply {
        schedule(HANG_MS) {
            val keys = runCatching { trace.toList() }.getOrDefault(emptyList())
            val dump = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                "\"${thread.name}\" ${thread.state}\n" + stack.joinToString("\n") { "    at $it" }
            }
            File("build/fuzz").apply { mkdirs() }.resolve("hang-$seed.txt")
                .writeText("Stuck after ${keys.size} keys:\n${keys.takeLast(KEY_TRAIL).joinToString("\n")}\n\n$dump")
        }
    }

    private fun ComposeUiTest.walk(server: FakeServer, uncaught: List<Throwable>) {
        val random = Random(seed)
        val pressed = ArrayDeque<String>()
        fun failWith(reason: String, cause: Throwable? = null): Nothing {
            val out = File("build/fuzz").apply { mkdirs() }
            runCatching { ImageIO.write(topRoot().captureToImage().toAwtImage(), "png", File(out, "failure.png")) }
            runCatching { File(out, "failure-semantics.txt").writeText(onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)) }
            File(out, "trace-$seed.txt").writeText(trace.joinToString("\n"))
            val message = "$reason (seed $seed, step ${pressed.size}); last keys: ${pressed.takeLast(KEY_TRAIL).joinToString(" ")}"
            if (cause != null) throw AssertionError(message, cause) else fail(message)
        }
        repeat(steps) { step ->
            val press = script?.get(step) ?: PRESSES.weightedPick(random)
            pressed += press.name
            try {
                send(press)
                waitForIdle()
            } catch (e: Throwable) {
                failWith("the app threw on ${press.name}", e)
            }
            uncaught.firstOrNull()?.let { failWith("a coroutine or thread threw", it) }
            // Focus may move through a frame with no owner while a list scrolls a target in; it must land somewhere.
            if (focusedCount() == 0) {
                runCatching { waitUntil(timeoutMillis = FOCUS_GRACE_MS) { focusedCount() > 0 } }
                    .onFailure { failWith("focus was lost after ${press.name}") }
            }
            val label = focusedLabel()
            label?.let { visited[it] = (visited[it] ?: 0) + 1 }
            trace += "${press.name}\t$label"
            if (server.openSessions.size > 1) failWith("the app holds ${server.openSessions.size} playback sessions")
        }
    }

    /**
     * Sends the key the way a window does: through the scene, so an open popup, menu or dialog takes it before the
     * screen behind it, then an Escape nothing took on as a back event.
     */
    @OptIn(InternalComposeUiApi::class)
    private fun ComposeUiTest.send(press: Press) {
        val scene = (this as SkikoComposeUiTest).scene
        fun dispatch(type: KeyEventType) = runOnUiThread {
            val event = KeyEvent(press.key, type)
            if (!scene.sendKeyEvent(event)) backInput.onKeyEvent(event)
        }
        dispatch(KeyEventType.KeyDown)
        if (press.held) {
            // A held key repeats its key-down, as a keyboard or a remote does, once the repeat delay has passed.
            mainClock.advanceTimeBy(REPEAT_DELAY_MS)
            repeat(((HOLD_MS - REPEAT_DELAY_MS) / REPEAT_MS).toInt()) {
                dispatch(KeyEventType.KeyDown)
                mainClock.advanceTimeBy(REPEAT_MS)
            }
        } else {
            mainClock.advanceTimeBy(TAP_MS)
        }
        dispatch(KeyEventType.KeyUp)
    }

    private fun ComposeUiTest.topRoot() = onAllNodes(isRoot()).onLast()

    private fun ComposeUiTest.focusedLabel(): String? {
        val node = onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull() ?: return null
        val config = node.config
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: config.getOrNull(SemanticsProperties.EditableText)?.let { "field" }
        return text?.take(LABEL_CHARS) ?: "unlabeled"
    }

    private fun ComposeUiTest.focusedCount() = onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().size

    /** What a desktop window does with an Escape key-down nothing took: a back event. */
    private class EscapeBackInput : NavigationEventInput() {
        fun onKeyEvent(event: KeyEvent) {
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) dispatchOnBackCompleted()
        }
    }

    private class Press(val name: String, val key: Key, val weight: Int, val held: Boolean = false)

    private fun List<Press>.weightedPick(random: Random): Press {
        var roll = random.nextInt(sumOf { it.weight })
        return first { press -> (roll < press.weight).also { roll -= press.weight } }
    }

    /**
     * A server with a lineup, a day of guide and playback sessions whose streams go nowhere, since the test has no
     * decoder; it counts the guide queries and the sessions the app holds open.
     */
    private class FakeServer {
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${http.address.port}"
        val guideQueries = AtomicInteger()
        /** Each open session's channel and intent, so a re-read or a renewal answers with what the app opened. */
        val openSessions: MutableMap<String, Pair<String, String>> = Collections.synchronizedMap(mutableMapOf())
        val sessionsOpened = AtomicInteger()
        private val groups = listOf("News", "Sports", "Movies", "Kids", "Music")
        private val channels = List(CHANNELS) { i -> "ch$i" }

        init {
            http.createContext("/api/v1/") { exchange ->
                try {
                    handle(exchange)
                } finally {
                    exchange.close()
                }
            }
            http.start()
        }

        fun stop() = http.stop(0)

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path.removePrefix("/api/v1/")
            val body = exchange.requestBody.readBytes().decodeToString()
            when {
                path == "pair" -> exchange.json(200, """{"apiToken":"${"f".repeat(64)}","serverId":"fuzz"}""")
                path == "bootstrap" -> exchange.json(
                    200,
                    """{"serverId":"fuzz","serverTime":"${Clock.System.now()}","apiVersion":1,""" +
                        """"capabilities":{"catalog":true,"guide":true,"playback":true,"viewers":true,"recordings":true}}""",
                )
                path == "channels" -> exchange.json(200, catalog())
                path == "guide/query" -> {
                    guideQueries.incrementAndGet()
                    exchange.json(200, guide(body))
                }
                path.startsWith("preferences/") -> exchange.json(200, if (exchange.requestMethod == "PUT") body else "{}")
                path == "recordings" && exchange.requestMethod == "GET" -> exchange.json(200, """{"recordings":[]}""")
                path == "recording-rules" && exchange.requestMethod == "GET" -> exchange.json(200, """{"rules":[]}""")
                path == "playback-sessions" -> {
                    val id = UUID.randomUUID().toString()
                    val opened = (CHANNEL_ID.find(body)?.groupValues?.get(1) ?: channels[0]) to (INTENT.find(body)?.groupValues?.get(1) ?: "watch")
                    openSessions[id] = opened
                    sessionsOpened.incrementAndGet()
                    exchange.json(201, session(id, opened.first, opened.second))
                }
                path.endsWith("/ad-break") -> exchange.json(200, """{"inBreak":false}""")
                path.startsWith("playback-sessions/") -> {
                    val id = path.removePrefix("playback-sessions/")
                    when {
                        exchange.requestMethod == "DELETE" -> {
                            openSessions -= id
                            exchange.sendResponseHeaders(204, -1)
                        }
                        else -> {
                            val open = openSessions[id]
                            if (open == null) {
                                exchange.json(410, """{"code":"SESSION_EXPIRED","message":"gone"}""")
                            } else {
                                val now = INTENT.find(body)?.groupValues?.get(1)?.let { open.first to it } ?: open
                                openSessions[id] = now
                                exchange.json(200, session(id, now.first, now.second))
                            }
                        }
                    }
                }
                else -> exchange.json(404, """{"code":"NOT_FOUND","message":"$path"}""")
            }
        }

        private fun catalog() = channels.mapIndexed { i, id ->
            """{"id":"$id","displayNumber":"${i + 2}","name":"Channel ${i + 2}","tags":["${groups[i % groups.size]}"],""" +
                """"legacyGuideId":"$id","guideStatus":"available","playbackEligible":true}"""
        }.joinToString(",", """{"revision":"r1","channels":[""", "]}")

        /** Half-hour programs on every channel the query names, across its window. */
        private fun guide(query: String): String {
            val ids = IDS.find(query)?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() } ?: channels
            val from = FROM.find(query)?.groupValues?.get(1)?.let(Instant::parse) ?: (Clock.System.now() - 1.hours)
            val to = TO.find(query)?.groupValues?.get(1)?.let(Instant::parse) ?: (from + 24.hours)
            val slots = generateSequence(from) { it + 30.minutes }.takeWhile { it < to }.toList()
            return ids.joinToString(",", """{"revision":"g1","from":"$from","to":"$to","channels":[""", "]}") { id ->
                slots.joinToString(",", """{"channelId":"$id","status":"available","programmes":[""", "]}") { start ->
                    """{"id":"$id-${start.epochSeconds}","channelId":"$id","startsAt":"$start","endsAt":"${start + 30.minutes}",""" +
                        """"title":"Show at ${start.epochSeconds % 86_400 / 1_800}","description":"A program."}"""
                }
            }
        }

        private fun session(id: String, channel: String, intent: String) =
            """{"id":"$id","channelId":"$channel","intent":"$intent","expiresAt":"${Clock.System.now() + 45.minutes}",""" +
                """"descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/sessions/$id/1/x.m3u8","epoch":1}}"""

        private fun HttpExchange.json(status: Int, text: String) {
            val bytes = text.encodeToByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.write(bytes)
        }

        private companion object {
            val IDS = Regex(""""channelIds"\s*:\s*\[([^]]*)]""")
            val FROM = Regex(""""from"\s*:\s*"([^"]+)"""")
            val TO = Regex(""""to"\s*:\s*"([^"]+)"""")
            val CHANNEL_ID = Regex(""""channelId"\s*:\s*"([^"]+)"""")
            val INTENT = Regex(""""intent"\s*:\s*"([^"]+)"""")
        }
    }

    private companion object {
        const val CHANNELS = 40
        const val TAP_MS = 60L
        const val HANG_MS = 5 * 60_000L
        const val HOLD_MS = 900L
        const val REPEAT_DELAY_MS = 500L
        const val REPEAT_MS = 50L
        const val FOCUS_GRACE_MS = 3_000L
        const val KEY_TRAIL = 30
        const val LABEL_CHARS = 40

        /** The remote's keys, weighted the way people press them: mostly the D-pad, then OK and Back. */
        val PRESSES = listOf(
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
