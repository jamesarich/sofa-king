package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.admin.encoderAttention
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncoderChecksTest {
    private fun encoderOf(command: List<String>) = command[command.indexOf("-c:v") + 1]

    @Test fun `a named encoder that fails is recorded with FFmpeg's reason and raises a Home item`() = runTest {
        val checks = EncoderChecks("ffmpeg", MutableClock(), exists = { true }) { _, _ ->
            Attempt(false, 40, "Cannot load libcuda.so.1")
        }
        assertTrue(encoderAttention(checks).isEmpty(), "nothing before the check")
        val chosen = checks.verify(VideoEncoder(HardwareAccel.NVENC))
        assertEquals(HardwareAccel.NONE, chosen.accel)
        val verdict = checks.startup!!
        assertTrue(verdict.failed)
        assertEquals(HardwareAccel.NVENC, verdict.requested)
        assertEquals("Cannot load libcuda.so.1", verdict.trials.single().error)
        assertTrue("h264_nvenc" in verdict.trials.single().command)
        val item = encoderAttention(checks).single()
        assertEquals("The NVENC encoder does not work", item.title)
        assertTrue(item.detail!!.contains("libcuda"))
    }

    @Test fun `auto that falls back to software is no failure, and software needs no check`() = runTest {
        var runs = 0
        val checks = EncoderChecks("ffmpeg", MutableClock(), exists = { true }) { _, _ ->
            runs++
            Attempt(false)
        }
        assertEquals(HardwareAccel.NONE, checks.verify(VideoEncoder(HardwareAccel.AUTO)).accel)
        assertEquals(3, runs, "NVENC, VA-API and Quick Sync each tried")
        assertFalse(checks.startup!!.failed)
        assertTrue(encoderAttention(checks).isEmpty())

        runs = 0
        assertEquals(HardwareAccel.NONE, checks.verify(VideoEncoder()).accel)
        assertEquals(0, runs)
        assertTrue(checks.startup!!.trials.isEmpty())
    }

    @Test fun `the transcode test encodes the clip through software and each GPU there is, with its speed`() = runTest {
        val tried = mutableListOf<String>()
        val checks =
            EncoderChecks("ffmpeg", MutableClock(), exists = { it == VideoEncoder.NVIDIA_DEVICE }) { command, _ ->
                tried += encoderOf(command)
                assertTrue(command.any { it.startsWith("testsrc2=s=1920x1080") }, "a moving 1080p clip")
                Attempt(true, if (encoderOf(command) == "libx264") 2_500 else 500)
            }
        assertNull(checks.lastTest)
        val verdict = checks.test(VideoEncoder(HardwareAccel.NVENC))
        assertEquals(listOf("libx264", "h264_nvenc"), tried)
        assertEquals(listOf("2.0x", "10.0x"), verdict.trials.map { speed(it, verdict.clipSeconds) })
        assertEquals(verdict, checks.lastTest)
        assertEquals("Software (libx264) 2.0x; NVENC 10.0x", testSummary(verdict))
    }
}
