package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.recording
import dev.jamesrich.sofaking.contract.RecordingBreak
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommercialScannerTest {
    private class Sound(val levels: FloatArray? = null) : RecordingSound {
        var asked = 0

        override suspend fun levels(file: Path): FloatArray? {
            asked++
            return levels
        }

        override suspend fun points(file: Path): IntArray? = null
    }

    private fun loud(seconds: Int) = FloatArray(seconds * 10) { -20f }

    private fun quiet(seconds: Int) = FloatArray(seconds * 10) { -120f }

    /** A slate from 30 s to 55 s. */
    private val slate = loud(30) + quiet(25) + loud(30)

    private fun ads(sound: Sound, mode: LearnedBreaks = LearnedBreaks.ON) =
        RecordingAds(sound, { null as BreakLibrary? }, { mode })

    /** The scanner over a fresh store and a recording of [marked] breaks, scanned once; [check] reads the outcome. */
    private fun scan(
        comskip: Commercials?,
        learned: RecordingAds?,
        marked: List<RecordingBreak> = emptyList(),
        dir: Path,
        check: suspend (StoredRecording, Path) -> Unit,
    ) = runBlocking<Unit> {
        Files.createDirectories(dir)
        val clock = MutableClock()
        val store = openStore(dir.resolve("db"), clock)
        try {
            val files = RecordingFiles(dir.resolve("recordings"), { null })
            val file = Files.createDirectories(dir.resolve("recordings/TV/Show")).resolve("Show - S01E01.ts")
            Files.write(file, ByteArray(188) { 0x47 })
            val start = clock.now.minusSeconds(3600)
            store.addRecording(
                StoredRecording(
                    "r1", null, "c", "Channel C", start, start.plusSeconds(1800), Duration.ZERO, Duration.ZERO,
                    "Show", state = Wire.RecordingState.COMPLETED, path = "TV/Show/Show - S01E01.ts", bytes = 188,
                    createdAt = start, updatedAt = start, commercials = Wire.CommercialScan.SCANNING, breaks = marked,
                ),
            )
            CommercialScanner(store, files, comskip, learned, Mutex(), clock).scan("r1")
            check(assertNotNull(store.recording("r1")), file)
        } finally {
            store.close()
        }
    }

    private fun withDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("sofa-scanner-")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun missingComskip(dir: Path) = Commercials(dir.resolve("no-such-comskip").toString(), dir.resolve("work"))

    private fun fakeComskip(dir: Path, edl: String) =
        Commercials(CommercialsTest.fakeComskip(dir, edl).toString(), dir.resolve("work"))

    @Test fun `marker breaks give a done scan and an EDL with no Comskip at all`() = withDir { dir ->
        val marked = listOf(RecordingBreak(60_000, 120_000), RecordingBreak(900_000, 960_000))
        scan(missingComskip(dir), null, marked, dir) { recording, file ->
            assertEquals(Wire.CommercialScan.DONE, recording.commercials)
            assertEquals(marked, recording.breaks)
            assertEquals("60.00\t120.00\t3\n900.00\t960.00\t3\n", Files.readString(Commercials.sidecar(file)))
        }
    }

    @Test fun `a learned slate gives a done scan when Comskip is missing, and a missing Comskip alone fails it`() =
        withDir { dir ->
            scan(missingComskip(dir), ads(Sound(slate)), dir = dir) { recording, file ->
                assertEquals(Wire.CommercialScan.DONE, recording.commercials)
                assertEquals(listOf(RecordingBreak(30_000, 55_000)), recording.breaks)
                assertTrue(Files.exists(Commercials.sidecar(file)))
            }
            scan(missingComskip(dir), null, dir = dir.resolve("only")) { recording, file ->
                assertEquals(Wire.CommercialScan.FAILED, recording.commercials)
                assertEquals(emptyList(), recording.breaks)
                assertFalse(Files.exists(Commercials.sidecar(file)))
            }
        }

    @Test fun `no Comskip and nothing found is done with no breaks, and no source at all leaves the scan unset`() =
        withDir { dir ->
            scan(null, ads(Sound(loud(60))), dir = dir) { recording, _ ->
                assertEquals(Wire.CommercialScan.DONE, recording.commercials)
                assertEquals(emptyList(), recording.breaks)
            }
            scan(null, ads(Sound(slate), LearnedBreaks.OFF), dir = dir.resolve("off")) { recording, _ ->
                assertNull(recording.commercials)
                assertEquals(emptyList(), recording.breaks)
            }
        }

    @Test fun `a learned source that cannot read the audio fails the scan unless another answered`() = withDir { dir ->
        scan(null, ads(Sound(null)), dir = dir) { recording, _ ->
            assertEquals(Wire.CommercialScan.FAILED, recording.commercials)
        }
        scan(fakeComskip(dir, "100\t130\t3"), ads(Sound(null)), dir = dir.resolve("two")) { recording, _ ->
            assertEquals(Wire.CommercialScan.DONE, recording.commercials)
            assertEquals(listOf(RecordingBreak(100_000, 130_000)), recording.breaks)
        }
    }

    @Test fun `markers win where Comskip overlaps them, Comskip adds what they missed, and learning is skipped`() =
        withDir { dir ->
            val sound = Sound(slate)
            scan(
                fakeComskip(dir, "100\t130\t3\n300\t360\t3"),
                ads(sound),
                listOf(RecordingBreak(60_000, 120_000)),
                dir,
            ) { recording, file ->
                assertEquals(Wire.CommercialScan.DONE, recording.commercials)
                assertEquals(
                    listOf(RecordingBreak(60_000, 120_000), RecordingBreak(300_000, 360_000)),
                    recording.breaks,
                )
                assertEquals("60.00\t120.00\t3\n300.00\t360.00\t3\n", Files.readString(Commercials.sidecar(file)))
                assertEquals(0, sound.asked, "a recording with markers is not searched for guesses")
            }
        }

    @Test fun `Comskip and a learned slate that overlap are one break`() = withDir { dir ->
        scan(fakeComskip(dir, "50\t70\t3"), ads(Sound(slate)), dir = dir) { recording, _ ->
            assertEquals(listOf(RecordingBreak(30_000, 70_000)), recording.breaks)
        }
    }
}
