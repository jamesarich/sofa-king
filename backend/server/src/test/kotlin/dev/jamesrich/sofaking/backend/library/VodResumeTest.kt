package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A library file resumed between two key frames, converted by the real FFmpeg. */
class VodResumeTest {
    @Test fun `copied video and converted sound start together when a file resumes between key frames`() {
        val out = resumed("libx264", "h264.mkv")
        assertTogether(starts(out.resolve("seg0.ts")))
    }

    @Test fun `copied HEVC in fragmented MP4 starts with its sound for a player that ignores edit lists`() {
        val out = resumed("libx265", "hevc.mkv")
        val init = Files.readAllBytes(out.resolve(FMP4_INIT))
        assertFalse("elst" in String(init, Charsets.ISO_8859_1), "the tracks carry no edit list")
        val joined = out.resolve("joined.mp4")
        Files.write(joined, init + Files.readAllBytes(out.resolve("seg0.m4s")))
        assertTogether(starts(joined, "-ignore_editlist", "1"))
    }

    @Test fun `a converted picture and a track of sound alone are cut where they were asked to start`() {
        val film = MediaInfo(
            "matroska,webm",
            video = VideoStream("h264", height = 1080),
            audio = listOf(AudioStream(1, "dts", default = true)),
        )
        val song = MediaInfo("flac", audio = listOf(AudioStream(0, "flac", default = true)))
        val out = Path.of("/t/s")
        val converted = vodCommand("ffmpeg", Path.of("/m/a.mkv"), out, Wire.VodMode.TRANSCODE, RESUME_MS, film, null)
        val sound = vodCommand("ffmpeg", Path.of("/m/a.flac"), out, Wire.VodMode.AUDIO, RESUME_MS, song, null)
        assertFalse("-noaccurate_seek" in converted)
        assertFalse("-noaccurate_seek" in sound)
    }

    /**
     * A file with key frames 10 seconds apart and sound no player takes as it is, as a film with AC-3 has, resumed
     * 5 seconds past the key frame at 10 with its video copied and its sound converted; the folder it was written to.
     */
    private fun resumed(encoder: String, name: String): Path {
        val dir = Files.createTempDirectory("sofa-vod-resume-")
        val file = dir.resolve(name)
        val keyFrames = if (encoder == "libx265") {
            listOf("-x265-params", "keyint=250:min-keyint=250:scenecut=0:log-level=error")
        } else {
            listOf("-g", "250", "-keyint_min", "250", "-sc_threshold", "0")
        }
        run(
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=s=320x180:r=25:d=30", "-f", "lavfi", "-i",
            "sine=d=30", "-c:v", encoder, "-preset", "ultrafast", *keyFrames.toTypedArray(), "-c:a", "ac3",
            file.toString(),
        )
        val media = runBlocking { MediaProbe("ffprobe").probe(file) }!!
        val out = Files.createDirectories(dir.resolve("out"))
        run(*vodCommand("ffmpeg", file, out, Wire.VodMode.AUDIO, RESUME_MS, media, null).toTypedArray())
        return out
    }

    private fun assertTogether(starts: Map<String, Double>) {
        assertEquals(setOf("video", "audio"), starts.keys)
        val gap = abs(starts.getValue("video") - starts.getValue("audio"))
        assertTrue(gap < TOGETHER_SECONDS, "the sound starts $gap s from the picture: $starts")
    }

    /** Each stream's first packet time in [file], by its kind, read with FFprobe's [options]. */
    private fun starts(file: Path, vararg options: String): Map<String, Double> =
        listOf("video" to "v", "audio" to "a").mapNotNull { (kind, stream) ->
            ProcessBuilder(
                "ffprobe", "-v", "error", *options, "-select_streams", stream, "-show_entries", "packet=pts_time",
                "-of", "csv=p=0", file.toString(),
            ).start().inputStream.bufferedReader().readLines()
                .mapNotNull { it.trim(',').toDoubleOrNull() }
                .minOrNull()
                ?.let { kind to it }
        }.toMap()

    private fun run(vararg command: String) {
        assertEquals(0, ProcessBuilder(*command).inheritIO().start().waitFor(), command.joinToString(" "))
    }

    private companion object {
        const val RESUME_MS = 15_000L
        const val TOGETHER_SECONDS = 0.3
    }
}
