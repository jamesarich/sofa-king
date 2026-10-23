package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.contract.RecordingBreak
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommercialsTest {
    companion object {
        /** A stand-in for Comskip that writes [edl] for its input into the `--output` folder, the way Comskip names it. */
        fun fakeComskip(dir: Path, edl: String, exit: Int = 0): Path {
            val script = dir.resolve("comskip-${exit}.sh")
            Files.writeString(script, """
                #!/bin/sh
                for arg; do case "${'$'}arg" in --output=*) out="${'$'}{arg#--output=}";; esac; last="${'$'}arg"; done
                name=${'$'}(basename "${'$'}last"); printf '$edl\n' > "${'$'}out/${'$'}{name%.*}.edl"
                exit $exit
            """.trimIndent() + "\n")
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
            return script
        }
    }

    @Test fun `an EDL reads as commercial breaks in milliseconds, in order, skipping other actions and bad lines`() {
        val edl = "450.76\t452.28\t3\n180.28 272.04 3\n10\t20\t0\nnot a line\n30\t25\t3\n"
        assertEquals(listOf(RecordingBreak(180_280, 272_040), RecordingBreak(450_760, 452_280)), Commercials.parseEdl(edl))
        assertEquals(listOf(RecordingBreak(1000, 2000)), Commercials.parseEdl("1 2"))
    }

    @Test fun `breaks are kept beside the recording, none leave no EDL, and a failing run throws`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-comskip-")
        try {
            val recording = Files.createDirectories(dir.resolve("TV/Show")).resolve("Show - S01E01.ts").also { Files.write(it, byteArrayOf(0x47)) }
            val found = Commercials(fakeComskip(dir, "60.5\t90\t3").toString(), dir.resolve("work")).detect(recording)
            assertEquals(listOf(RecordingBreak(60_500, 90_000)), found)
            assertEquals("60.5\t90\t3\n", Files.readString(dir.resolve("TV/Show/Show - S01E01.edl")))
            Files.delete(dir.resolve("TV/Show/Show - S01E01.edl"))

            assertEquals(emptyList(), Commercials(fakeComskip(dir, "", exit = 1).toString(), dir.resolve("work")).detect(recording))
            assertFalse(Files.exists(dir.resolve("TV/Show/Show - S01E01.edl")))
            assertFailsWith<IllegalStateException> { Commercials(fakeComskip(dir, "", exit = 7).toString(), dir.resolve("work")).detect(recording) }
            assertFailsWith<java.io.IOException> { Commercials(dir.resolve("missing").toString(), dir.resolve("work")).detect(recording) }
            assertEquals(listOf("comskip.ini"), Files.list(dir.resolve("work")).use { files -> files.map { it.fileName.toString() }.toList() }, "no working folder is left")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `a run past the timeout fails that scan rather than cancelling its caller, and leaves no process`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-comskip-slow-")
        try {
            val recording = dir.resolve("Show - S01E01.ts").also { Files.write(it, byteArrayOf(0x47)) }
            val script = dir.resolve("slow.sh")
            Files.writeString(script, "#!/bin/sh\nexec sleep 30\n")
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
            val failure = assertFails { Commercials(script.toString(), dir.resolve("work"), timeout = 300.milliseconds).detect(recording) }
            assertIs<IllegalStateException>(failure)
            assertFalse(failure is kotlinx.coroutines.CancellationException, "a timeout must not read as a cancellation: $failure")
            assertEquals(0, ProcessHandle.current().children().filter { it.info().command().orElse("").endsWith("sleep") }.count())
        } finally { dir.toFile().deleteRecursively() }
    }

    /** Real Comskip on a programme, three 30-second ads between black frames, and the programme again; skipped without Comskip. */
    @Test fun `Comskip finds the ad block in a generated recording`() = runBlocking<Unit> {
        assumeTrue(runCatching { ProcessBuilder("comskip", "--help").redirectErrorStream(true).start().waitFor() }.isSuccess, "comskip is not installed")
        val dir = Files.createTempDirectory("sofa-comskip-real-")
        try {
            fun ffmpeg(vararg args: String) = assertEquals(0, ProcessBuilder(listOf("ffmpeg", "-loglevel", "error", "-y") + args).inheritIO().start().waitFor())
            fun segment(name: String, video: String, seconds: Int, tone: Int) = ffmpeg(
                "-f", "lavfi", "-i", "$video=size=320x240:rate=25:duration=$seconds", "-f", "lavfi", "-i", "sine=frequency=$tone:duration=$seconds",
                "-c:v", "mpeg2video", "-b:v", "1M", "-c:a", "mp2", "-ar", "48000", "-f", "mpegts", dir.resolve(name).toString(),
            )
            segment("p1.ts", "testsrc", 180, 440); segment("a1.ts", "smptebars", 30, 880); segment("a2.ts", "rgbtestsrc", 30, 1200)
            segment("a3.ts", "testsrc2", 30, 660); segment("p2.ts", "testsrc", 180, 440)
            ffmpeg("-f", "lavfi", "-i", "color=black:size=320x240:rate=25:duration=0.6", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono", "-t", "0.6",
                "-c:v", "mpeg2video", "-b:v", "1M", "-c:a", "mp2", "-ar", "48000", "-f", "mpegts", dir.resolve("b.ts").toString())
            val parts = listOf("p1", "b", "a1", "b", "a2", "b", "a3", "b", "p2").joinToString("|") { dir.resolve("$it.ts").toString() }
            val show = dir.resolve("show.ts")
            ffmpeg("-i", "concat:$parts", "-c", "copy", show.toString())

            val breaks = Commercials("comskip", dir.resolve("work")).detect(show)
            val ads = assertNotNull(breaks.firstOrNull { it.startMs in 170_000..190_000 }, "breaks: $breaks")
            assertTrue(ads.endMs in 262_000..282_000, "breaks: $breaks")
            assertTrue(Files.exists(dir.resolve("show.edl")))
        } finally { dir.toFile().deleteRecursively() }
    }
}
