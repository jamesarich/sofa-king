package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.AppDriver.Companion.REMOTE
import java.io.File
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.random.Random
import kotlin.test.Test

/**
 * Drives the whole app with random remote keys against a [FakeServer] and fails when a key throws, focus is lost and
 * does not come back, or the app holds more than one playback session. It runs once in the desktop layout and once in
 * the TV layout.
 *
 * `SOFAKING_FUZZ_SEED` and `SOFAKING_FUZZ_STEPS` replay a failure's keys or run a longer soak, and `SOFAKING_FUZZ_KEYS`
 * presses a fixed list of keys by name instead. The ViewModels run on the Swing thread in real time beside the test
 * (the test's UI thread needs Main, so it cannot be swapped for a test dispatcher), so a seed replays the same keys,
 * not always the same timing. Each run writes a trace of the keys and what they left focused to
 * build/fuzz/desktop or build/fuzz/tv; a failure adds the screen and its semantics tree.
 */
class AppKeyFuzzTest {
    private val seed = System.getenv("SOFAKING_FUZZ_SEED")?.toLongOrNull() ?: 1L
    private val script = System.getenv("SOFAKING_FUZZ_KEYS")?.split(',')?.map { name ->
        REMOTE.first { it.name == name.trim() }
    }
    private val steps = script?.size ?: System.getenv("SOFAKING_FUZZ_STEPS")?.toIntOrNull() ?: 300

    /** What each key left focused, by its label, so a run that sits in one corner of the app shows it. */
    private val visited = mutableMapOf<String, Int>()
    private val trace = mutableListOf<String>()

    @Test
    fun `random keys never crash the desktop layout, lose focus or leak a session`() = fuzz(tv = false)

    @Test
    fun `random remote keys never crash the TV layout, lose focus or leak a session`() = fuzz(tv = true)

    private fun fuzz(tv: Boolean) {
        val out = File("build/fuzz", if (tv) "tv" else "desktop")
        val watchdog = hangWatchdog(out)
        try {
            runSofaKing(tv, out) {
                val started = System.nanoTime()
                walk()
                println(
                    "Key fuzz (${out.name}): seed $seed, $steps keys in " +
                        "${(System.nanoTime() - started) / 1_000_000_000} s, ${server.sessionsOpened.get()} sessions " +
                        "opened, ${server.guideQueries.get()} guide queries, ${visited.size} focus targets",
                )
                out.resolve("visited-$seed.txt")
                    .writeText(
                        visited.entries.sortedByDescending {
                            it.value
                        }.joinToString("\n") { "${it.value}\t${it.key}" },
                    )
                out.resolve("trace-$seed.txt").writeText(trace.joinToString("\n"))
            }
        } finally {
            watchdog.cancel()
        }
    }

    /**
     * A run stuck past [HANG_MS] leaves every thread's stack and the keys so far in build/fuzz, which CI uploads,
     * since a hung test otherwise reports nothing before the job's own timeout cancels it.
     */
    private fun hangWatchdog(out: File) = Timer("fuzz-watchdog", true).apply {
        schedule(HANG_MS) {
            val keys = runCatching { trace.toList() }.getOrDefault(emptyList())
            val dump = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                "\"${thread.name}\" ${thread.state}\n" + stack.joinToString("\n") { "    at $it" }
            }
            out.mkdirs()
            out.resolve("hang-$seed.txt")
                .writeText("Stuck after ${keys.size} keys:\n${keys.takeLast(KEY_TRAIL).joinToString("\n")}\n\n$dump")
        }
    }

    private fun AppDriver.walk() {
        val random = Random(seed)
        val pressed = ArrayDeque<String>()
        fun failAt(reason: String, cause: Throwable? = null): Nothing {
            out.resolve("trace-$seed.txt").writeText(trace.joinToString("\n"))
            failWith(
                "$reason (seed $seed, step ${pressed.size}); last keys: " +
                    "${pressed.takeLast(KEY_TRAIL).joinToString(" ")}",
                cause,
            )
        }
        repeat(steps) { step ->
            val key = script?.get(step) ?: REMOTE.weightedPick(random)
            pressed += key.name
            try {
                press(key)
            } catch (e: Throwable) {
                failAt("the app threw on ${key.name}", e)
            }
            uncaught.firstOrNull()?.let { failAt("a coroutine or thread threw", it) }
            // Focus may move through a frame with no owner while a list scrolls a target in; it must land somewhere.
            if (focusedCount() == 0) {
                runCatching { ui.waitUntil(timeoutMillis = FOCUS_GRACE_MS) { focusedCount() > 0 } }
                    .onFailure { failAt("focus was lost after ${key.name}") }
            }
            val label = focusedLabel()
            label?.let { visited[it] = (visited[it] ?: 0) + 1 }
            trace += "${key.name}\t$label"
            if (server.openSessions > 1) failAt("the app holds ${server.openSessions} playback sessions")
        }
    }

    private fun List<AppDriver.Press>.weightedPick(random: Random): AppDriver.Press {
        var roll = random.nextInt(sumOf { it.weight })
        return first { press -> (roll < press.weight).also { roll -= press.weight } }
    }

    private companion object {
        const val HANG_MS = 5 * 60_000L
        const val FOCUS_GRACE_MS = 3_000L
        const val KEY_TRAIL = 30
    }
}
