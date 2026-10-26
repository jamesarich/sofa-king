package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.contract.ClientProfile
import dev.jamesrich.sofaking.contract.Wire
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicPlaybackTest {
    private fun track(container: String, codec: String) =
        MediaInfo(container, 200_000, audio = listOf(AudioStream(0, codec, 2)), picture = true)

    private val phone = ClientProfile(listOf("mp4", "mkv"), listOf("h264"), listOf("aac", "mp3", "flac", "opus"))

    @Test fun `a track plays as it is when the client decodes it in its own file, else its sound is converted`() {
        assertEquals(Wire.VodMode.DIRECT, vodMode(track("flac", "flac"), phone))
        assertEquals(Wire.VodMode.DIRECT, vodMode(track("mp3", "mp3"), phone))
        assertEquals(Wire.VodMode.DIRECT, vodMode(track("mov,mp4,m4a,3gp,3g2,mj2", "aac"), phone))
        assertEquals(Wire.VodMode.DIRECT, vodMode(track("ogg", "opus"), phone))
        assertEquals(Wire.VodMode.AUDIO, vodMode(track("mov,mp4,m4a,3gp,3g2,mj2", "alac"), phone))
        assertEquals(Wire.VodMode.AUDIO, vodMode(track("wav", "pcm_s16le"), phone))
        assertEquals(Wire.VodMode.AUDIO, vodMode(track("flac", "flac"), phone.copy(audioCodecs = listOf("aac"))))
        assertEquals(
            Wire.VodMode.AUDIO,
            vodMode(track("mov,mp4,m4a,3gp,3g2,mj2", "aac"), phone.copy(containers = emptyList())),
            "an M4A needs a player that opens MP4",
        )
        assertEquals("flac", extension(track("flac", "flac")))
        assertEquals("audio/flac", directType(track("flac", "flac")))
        assertEquals("m4a", extension(track("mov,mp4,m4a,3gp,3g2,mj2", "aac")))
        assertEquals("audio/mp4", directType(track("mov,mp4,m4a,3gp,3g2,mj2", "aac")))
    }

    @Test fun `a converted track is its sound alone in AAC, without the cover picture inside it`() {
        val dir = Files.createTempDirectory("sofa-music-play-")
        val cover = dir.resolve("cover.jpg")
        val file = dir.resolve("song.flac")
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=red:s=64x64:d=1", "-frames:v", "1", cover.toString())
        run(
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=d=8", "-i", cover.toString(),
            "-map", "0:a", "-map", "1", "-c:a", "flac",
            "-c:v", "copy", "-disposition:v", "attached_pic", file.toString(),
        )
        val media = kotlinx.coroutines.runBlocking { MediaProbe("ffprobe").probe(file) }!!
        assertTrue(media.picture)
        val out = Files.createDirectories(dir.resolve("out"))
        val command = vodCommand("ffmpeg", file, out, Wire.VodMode.AUDIO, 0, media, null)
        assertFalse(command.any { it.startsWith("0:v") }, command.joinToString(" "))
        run(*command.toTypedArray())
        val segment = out.resolve("seg0.ts")
        assertTrue(Files.isRegularFile(out.resolve("index.m3u8")))
        val streams = ProcessBuilder(
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "stream=codec_type,codec_name",
            "-of",
            "csv=p=0",
            segment.toString(),
        ).start().inputStream.bufferedReader().readText()
        // MPEG-TS lists its streams once more under its program.
        assertEquals(setOf("aac,audio"), streams.lines().filter { it.isNotBlank() }.toSet())
    }

    private fun run(vararg command: String) {
        assertEquals(0, ProcessBuilder(*command).inheritIO().start().waitFor(), command.joinToString(" "))
    }
}
