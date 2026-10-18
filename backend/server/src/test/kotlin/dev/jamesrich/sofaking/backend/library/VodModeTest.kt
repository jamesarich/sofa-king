package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.contract.ClientProfile
import dev.jamesrich.sofaking.contract.Wire
import java.nio.file.Path
import kotlin.test.*

class VodModeTest {
    private fun media(container: String, video: String, audio: String, height: Int = 1080, transfer: String? = null) = MediaInfo(
        container, 60_000, video = VideoStream(video, height = height, transfer = transfer),
        audio = listOf(AudioStream(1, audio, 6, "eng", default = true), AudioStream(2, "aac", 2, "eng")),
    )
    private val tv = ClientProfile(listOf("mp4", "mkv", "ts"), listOf("h264", "hevc"), listOf("aac", "ac3", "eac3"), maxHeight = 2160, hdr = true)
    private val browser = ClientProfile(listOf("mp4", "webm"), listOf("h264", "vp9"), listOf("aac", "opus"), maxHeight = 1080)

    @Test fun `what the client plays as is goes direct`() {
        assertEquals(Wire.VodMode.DIRECT, vodMode(media("matroska,webm", "hevc", "eac3", 2160, "smpte2084"), tv))
        assertEquals(Wire.VodMode.DIRECT, vodMode(media("matroska,webm", "vp9", "opus"), browser), "a WebM in Matroska's format name")
    }

    @Test fun `a container the client lacks is remuxed, audio it lacks converted, and the rest transcoded`() {
        assertEquals(Wire.VodMode.REMUX, vodMode(media("matroska,webm", "h264", "aac"), browser))
        assertEquals(Wire.VodMode.AUDIO, vodMode(media("mov,mp4,m4a,3gp,3g2,mj2", "h264", "dts"), browser))
        assertEquals(Wire.VodMode.TRANSCODE, vodMode(media("matroska,webm", "hevc", "aac"), browser), "a codec it lacks")
        assertEquals(Wire.VodMode.TRANSCODE, vodMode(media("matroska,webm", "h264", "aac", height = 2160), browser), "too tall")
        assertEquals(Wire.VodMode.TRANSCODE, vodMode(media("matroska,webm", "hevc", "eac3", 2160, "smpte2084"), tv.copy(hdr = false)), "HDR it cannot show")
        assertEquals(Wire.VodMode.TRANSCODE, vodMode(null, tv), "an unprobed file")
    }

    @Test fun `a picked audio stream decides the audio`() {
        val file = media("mov,mp4,m4a,3gp,3g2,mj2", "h264", "truehd")
        assertEquals(Wire.VodMode.AUDIO, vodMode(file, browser))
        assertEquals(Wire.VodMode.DIRECT, vodMode(file, browser, file.audio[1]))
    }

    @Test fun `the FFmpeg command copies or converts per mode and starts where asked`() {
        val file = media("matroska,webm", "h264", "dts")
        val command = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.AUDIO, 90_500, file, null)
        assertEquals(listOf("-ss", "90.500", "-i", "/m/a.mkv"), command.subList(command.indexOf("-ss"), command.indexOf("-ss") + 4))
        assertEquals(listOf("0:v:0?", "0:1"), command.withIndex().filter { it.value == "-map" }.map { command[it.index + 1] }, "the default audio stream")
        assertEquals("copy", command[command.indexOf("-c:v") + 1])
        assertEquals("aac", command[command.indexOf("-c:a") + 1])
        assertEquals("/t/s/index.m3u8", command.last())
        val converted = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.TRANSCODE, 0, file, 2)
        assertFalse("-ss" in converted)
        assertEquals("libx264", converted[converted.indexOf("-c:v") + 1])
        assertTrue("0:2" in converted)
    }

    @Test fun `a file still being written is followed until it stops growing`() {
        val command = vodCommand("ffmpeg", Path.of("/r/a.ts"), Path.of("/t/s"), Wire.VodMode.REMUX, 0, null, null, follow = true)
        assertEquals(listOf("-follow", "1", "-rw_timeout", "60000000", "-i", "/r/a.ts"), command.subList(command.indexOf("-follow"), command.indexOf("-follow") + 6))
        assertFalse("-follow" in vodCommand("ffmpeg", Path.of("/r/a.ts"), Path.of("/t/s"), Wire.VodMode.REMUX, 0, null, null))
    }
}
