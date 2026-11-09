package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.ServerEvent
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.notify.NotifyEvent
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.recordings
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The space cull's budget: what a pass deletes is bounded by the space a recording needs and by a daily count. */
class DvrCullTest {
    private class Rig(
        val dvr: Dvr,
        val clock: MutableClock,
        val root: Path,
        val events: List<ServerEvent>,
        val store: dev.jamesrich.sofaking.backend.store.BackendStore,
    ) {
        fun files() = Files.walk(root).use { found -> found.filter(Files::isRegularFile).count().toInt() }

        suspend fun left() = store.recordings().filter {
            it.state == Wire.RecordingState.COMPLETED
        }.map { it.id }.toSet()
    }

    /** A DVR over [count] finished one-byte files, the first [watched] of which are watched; the floor is 5 GiB. */
    private fun rig(count: Int, watched: Int = 0, free: (filesLeft: Int) -> Long, block: suspend Rig.() -> Unit) =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-dvr-budget-")
            Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
            val config =
                ServerConfig("127.0.0.1", 8080, dir, dir.resolve("sources.json"), updateImage = null, comskip = null)
            val runtime = LiveRuntime.create(openStore(dir), config, Clock.systemUTC(), probes = false)
            val clock = MutableClock()
            val store = openStore(dir.resolve("own"), clock)
            val root = dir.resolve("own/recordings")
            val serverEvents = ServerEvents(clock)
            val seen = java.util.Collections.synchronizedList(mutableListOf<ServerEvent>())
            try {
                coroutineScope {
                    val listening =
                        launch(start = CoroutineStart.UNDISPATCHED) { serverEvents.events.collect { seen += it } }
                    lateinit var rig: Rig
                    val dvr = Dvr(
                        store, runtime.lineup, runtime.guides,
                        Recorder("ffmpeg", runtime.sessions, runtime.feeds, clock, runtime.loopback),
                        root, clock, { 4 },
                        retention = { Retention(freeGib = 5) },
                        freeSpace = { free(rig.files()) },
                        events = serverEvents,
                    )
                    rig = Rig(dvr, clock, root, seen, store)
                    for (n in 1..count) {
                        val start = clock.instant().minus(Duration.ofDays(n.toLong()))
                        val path = "TV/Show/r$n.ts"
                        Files.createDirectories(root.resolve(path).parent)
                        Files.write(root.resolve(path), byteArrayOf(0x47))
                        store.addRecording(
                            StoredRecording(
                                "r$n", null, "c", "C", start, start.plusSeconds(1800),
                                Duration.ZERO, Duration.ZERO, "r$n",
                                state = Wire.RecordingState.COMPLETED, path = path, bytes = 1,
                                watched = n > count - watched, createdAt = start, updatedAt = start,
                            ),
                        )
                    }
                    rig.block()
                    listening.cancel()
                }
            } finally {
                store.close()
                runtime.close()
                runtime.store.close()
                dir.toFile().deleteRecursively()
            }
        }

    private fun Rig.scheduleFromNow(opensIn: Duration, length: Duration) {
        val start = clock.instant().plus(opensIn)
        runBlocking {
            store.addRecording(
                StoredRecording(
                    "next", null, "c", "C", start, start.plus(length), Duration.ZERO, Duration.ZERO, "Next",
                    state = Wire.RecordingState.SCHEDULED, createdAt = start, updatedAt = start,
                ),
            )
        }
    }

    @Test fun `a disk whose free space never rises loses at most the daily budget across many passes`() =
        rig(count = 60, free = { Dvr.GIB }) {
            repeat(40) {
                dvr.cull("test")
                clock.now = clock.now.plus(Duration.ofMinutes(30))
            }
            assertEquals(60 - CullBudget.DAILY_MAX, left().size)
            assertEquals(CullBudget.DAILY_MAX, events.count { it.type == NotifyEvent.RECORDING_CULLED })
        }

    @Test fun `cleanup goes on once the budget window has moved`() = rig(count = 60, free = { Dvr.GIB }) {
        repeat(40) {
            dvr.cull("test")
            clock.now = clock.now.plus(Duration.ofMinutes(30))
        }
        clock.now = clock.now.plus(Duration.ofHours(25))
        dvr.cull("test")
        assertEquals(60 - CullBudget.DAILY_MAX - 1, left().size)
    }

    @Test fun `a disk low past the budget raises disk low once and deletes nothing more`() = rig(
        count = 30,
        free = { left -> Dvr.GIB + (30 - left) * Dvr.GIB / 1024 },
    ) {
        repeat(5) {
            dvr.cull("test")
            clock.now = clock.now.plus(Duration.ofMinutes(30))
        }
        val remaining = left().size
        assertEquals(30 - CullBudget.DAILY_MAX, remaining)
        assertEquals(1, events.count { it.type == "disk.low" })
        repeat(3) {
            dvr.cull("test")
            clock.now = clock.now.plus(Duration.ofMinutes(30))
        }
        assertEquals(remaining, left().size)
        assertEquals(1, events.count { it.type == "disk.low" })
    }

    @Test fun `a recording that needs 2 GiB culls only until that much is free beside the floor`() = rig(
        count = 12,
        free = { left -> Dvr.GIB + (12 - left) * Dvr.GIB },
    ) {
        // A 30 minute recording opens in ten minutes: 2 GiB at the default rate, plus the 5 GiB floor, is 7 GiB.
        scheduleFromNow(Duration.ofMinutes(10), Duration.ofMinutes(30))
        dvr.cull("test")
        assertEquals(12 - 6, left().size)
    }

    @Test fun `with no recording due cleanup stops at the floor alone`() = rig(
        count = 12,
        free = { left -> Dvr.GIB + (12 - left) * Dvr.GIB },
    ) {
        dvr.cull("test")
        assertEquals(12 - 4, left().size)
    }

    @Test fun `unwatched recordings survive when the watched ones were enough`() = rig(
        count = 8,
        watched = 3,
        free = { left -> 3 * Dvr.GIB + (8 - left) * Dvr.GIB },
    ) {
        dvr.cull("test")
        assertEquals(setOf("r1", "r2", "r3", "r4", "r5", "r6"), left())
    }
}
