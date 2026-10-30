package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.recording
import dev.jamesrich.sofaking.contract.RecordingBreak
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecordingRunnerTest {
    private fun finishing(scan: Boolean, check: suspend (StoredRecording, Path, List<String>) -> Unit) =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-runner-")
            Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
            val config =
                ServerConfig("127.0.0.1", 8080, dir, dir.resolve("sources.json"), updateImage = null, comskip = null)
            val runtime = LiveRuntime.create(openStore(dir), config, Clock.systemUTC(), probes = false)
            val clock = MutableClock()
            val store = openStore(dir.resolve("own"), clock)
            val root = dir.resolve("own/recordings")
            try {
                val start = clock.now.minusSeconds(3600)
                val partial = RecordingLayout.inProgress(
                    RecordingLayout.path(RecordingTitle("News"), start, ZoneOffset.UTC) { false },
                )
                Files.createDirectories(root.resolve(partial).parent)
                Files.write(root.resolve(partial), ByteArray(188 * 100) { 0x47 })
                val row = StoredRecording(
                    "r1", null, "c", "Channel C", start, start.plusSeconds(1800), Duration.ZERO, Duration.ZERO, "News",
                    state = Wire.RecordingState.RECORDING, path = partial.toString(),
                    createdAt = start, updatedAt = start,
                )
                store.addRecording(row)
                val scanned = ArrayList<String>()
                val files = RecordingFiles(root, { null })
                val recorder = Recorder("ffmpeg", runtime.sessions, runtime.feeds, clock, runtime.loopback)
                val queue = if (scan) { id: String -> scanned += id } else null
                val runner = RecordingRunner(store, recorder, files, clock, ZoneOffset.UTC, Mutex(), queue, null) {}
                runner.finish(
                    row,
                    RecordedTake(
                        188 * 100L,
                        null,
                        listOf(RecordingBreak(120_000, 180_000), RecordingBreak(600_000, 660_000)),
                    ),
                )
                check(assertNotNull(store.recording("r1")), root, scanned)
            } finally {
                store.close()
                runtime.close()
                runtime.store.close()
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `marker breaks are kept on the recording with an EDL beside it, and the scan is queued`() =
        finishing(scan = true) { recording, root, scanned ->
            assertEquals(Wire.RecordingState.COMPLETED, recording.state)
            assertEquals(Wire.CommercialScan.SCANNING, recording.commercials)
            assertEquals(listOf(RecordingBreak(120_000, 180_000), RecordingBreak(600_000, 660_000)), recording.breaks)
            val file = root.resolve(assertNotNull(recording.path))
            assertEquals("120.00\t180.00\t3\n600.00\t660.00\t3\n", Files.readString(Commercials.sidecar(file)))
            assertEquals(listOf("r1"), scanned)
        }

    @Test fun `without a scanner the markers alone make the commercial scan done`() =
        finishing(scan = false) { recording, root, _ ->
            assertEquals(Wire.CommercialScan.DONE, recording.commercials)
            assertEquals(2, recording.breaks.size)
            assertTrue(Files.exists(Commercials.sidecar(root.resolve(assertNotNull(recording.path)))))
        }
}
