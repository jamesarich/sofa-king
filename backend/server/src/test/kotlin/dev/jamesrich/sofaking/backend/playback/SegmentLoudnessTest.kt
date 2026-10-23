package dev.jamesrich.sofaking.backend.playback

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class SegmentLoudnessTest {
    @Test
    fun `digital silence has the floor level and a full-scale square wave is near 0 dBFS`() {
        assertContentEquals(FloatArray(2) { SILENT_DB }, loudness(ByteArray(3_200)))
        val square = ByteArray(1_600) { if (it % 2 == 0) 0xFF.toByte() else if (it % 4 == 1) 0x7F else 0x80.toByte() }
        assertEquals(0f, loudness(square).single(), 0.1f)
        assertEquals(0, loudness(ByteArray(1_000)).size, "a part window has no level")
    }

    @Test
    fun `FFmpeg reads a segment's sound and silence, and a segment without audio gives nothing`() = runBlocking {
        val dir = Files.createTempDirectory("loudness")
        try {
            fun segment(name: String, vararg inputs: String): ByteArray {
                val file = dir.resolve(name)
                val exit = ProcessBuilder(listOf("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=black:s=64x64:d=4") + inputs +
                    listOf("-c:v", "mpeg2video", "-c:a", "aac", "-f", "mpegts", file.toString()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
                assertEquals(0, exit, "ffmpeg makes the test segment")
                return Files.readAllBytes(file)
            }
            val levels = assertNotNull(SegmentLoudness("ffmpeg").levels(segment("tone.ts", "-f", "lavfi", "-i", "sine=f=440:d=2,apad=whole_dur=4")))
            assertTrue(levels.size in 38..42, "4 s of levels: ${levels.size}")
            assertTrue(levels.drop(2).take(15).all { it > -30f }, levels.joinToString())
            assertTrue(levels.takeLast(15).all { it < -60f }, levels.joinToString())
            assertNull(SegmentLoudness("ffmpeg").levels(segment("mute.ts")))
            assertNull(SegmentLoudness("ffmpeg").levels(ByteArray(188 * 10) { 1 }))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
