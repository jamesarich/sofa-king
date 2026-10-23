package dev.jamesrich.sofaking.backend.playback

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SegmentAudioTest {
    @Test
    fun `digital silence has the floor level and a full-scale square wave is near 0 dBFS`() {
        assertContentEquals(FloatArray(2) { SILENT_DB }, loudness(ByteArray(3_200), sampleRate = 8_000))
        val square = ByteArray(1_600) { if (it % 2 == 0) 0xFF.toByte() else if (it % 4 == 1) 0x7F else 0x80.toByte() }
        assertEquals(0f, loudness(square, sampleRate = 8_000).single(), 0.1f)
        assertEquals(0, loudness(ByteArray(1_000), sampleRate = 8_000).size, "a part window has no level")
    }

    private fun ffmpeg(vararg args: String) {
        val exit = ProcessBuilder(listOf("ffmpeg", "-nostdin", "-loglevel", "error", "-y") + args)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        assertEquals(0, exit, "ffmpeg makes the test media")
    }

    private fun <T> inTemp(block: suspend CoroutineScope.(Path) -> T): T = runBlocking {
        val dir = Files.createTempDirectory("segment-audio")
        try { block(dir) } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `FFmpeg reads a segment's sound and silence, and a segment without audio gives nothing`() = inTemp { dir ->
        fun segment(name: String, vararg inputs: String): ByteArray {
            val file = dir.resolve(name)
            ffmpeg(*(arrayOf("-f", "lavfi", "-i", "color=black:s=64x64:d=4") + inputs + arrayOf("-c:v", "mpeg2video", "-c:a", "aac", "-f", "mpegts", file.toString())))
            return Files.readAllBytes(file)
        }
        val audio = SegmentAudio("ffmpeg")
        val levels = loudness(assertNotNull(audio.pcm(segment("tone.ts", "-f", "lavfi", "-i", "sine=f=440:d=2,apad=whole_dur=4"))))
        assertTrue(levels.size in 38..42, "4 s of levels: ${levels.size}")
        assertTrue(levels.drop(2).take(15).all { it > -30f }, levels.joinToString())
        assertTrue(levels.takeLast(15).all { it < -60f }, levels.joinToString())
        assertNull(audio.pcm(segment("mute.ts")))
        assertNull(audio.pcm(ByteArray(188 * 10) { 1 }))
    }

    @Test
    fun `segment by segment the follower gives the points of the whole stream, each placed on the timeline`() = inTemp { dir ->
        ffmpeg("-f", "lavfi", "-i", "anoisesrc=d=24:c=pink:a=0.3:seed=3,lowpass=3000", "-f", "lavfi", "-i", "sine=f=330:d=24", "-filter_complex", "[0][1]amix=inputs=2",
            "-c:a", "mp2", "-f", "segment", "-segment_time", "6", "-segment_format", "mpegts", dir.resolve("s%d.ts").toString())
        ffmpeg("-f", "lavfi", "-i", "anoisesrc=d=24:c=pink:a=0.3:seed=3,lowpass=3000", "-f", "lavfi", "-i", "sine=f=330:d=24", "-filter_complex", "[0][1]amix=inputs=2",
            "-ar", AUDIO_RATE.toString(), "-ac", "1", "-f", "s16le", dir.resolve("whole.pcm").toString())
        val audio = SegmentAudio("ffmpeg")
        val whole = assertNotNull(audio.points(Files.readAllBytes(dir.resolve("whole.pcm"))))
        val heard = ArrayList<Pair<IntArray, Long>>()
        val done = CompletableDeferred<Unit>()
        val segments = (0..3).map { "https://example.test/s$it.ts" }
        // The follower idles a while before it stops; the test's own scope ends it.
        val scope = CoroutineScope(Dispatchers.Default)
        val follower = AudioFollower(audio, scope, placement = { url -> Placement("p", segments.indexOf(url).toLong(), segments.indexOf(url) * 6_000L) }) { url, _, points, firstMs ->
            if (points != null) heard += points to firstMs
            if (url == segments.last()) done.complete(Unit)
        }
        segments.forEachIndexed { n, url -> follower.offer(url, Files.readAllBytes(dir.resolve("s$n.ts"))) }
        try { withTimeout(50_000) { done.await() } } finally { scope.cancel() }
        val points = heard.flatMap { it.first.toList() }
        assertTrue(points.size in whole.size - 3..whole.size, "${points.size} points of ${whole.size}")
        // Each segment decodes on its own, so a few samples at its edges may differ from the whole; the points barely do.
        val close = points.indices.count { Integer.bitCount(points[it] xor whole[it]) <= 6 }
        assertTrue(close >= points.size * 0.95, "$close of ${points.size} points match the whole stream's")
        assertEquals(0L, heard.first().second)
        // Each read's first point is the one after the last read's, on the same 1365-sample grid.
        var expected = 0L
        for ((read, firstMs) in heard) {
            assertTrue(kotlin.math.abs(expected * CHROMAPRINT_HOP * 1_000 / AUDIO_RATE - firstMs) <= 2, "read at $firstMs, due at point $expected")
            expected += read.size
        }
    }
}
