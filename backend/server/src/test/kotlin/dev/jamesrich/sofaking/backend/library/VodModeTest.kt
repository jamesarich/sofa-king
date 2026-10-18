package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.contract.ClientProfile
import dev.jamesrich.sofaking.contract.Wire
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class VodModeTest {
    private fun media(container: String, video: String, audio: String, height: Int = 1080, transfer: String? = null, tag: String? = null) = MediaInfo(
        container, 60_000, video = VideoStream(video, height = height, transfer = transfer, tag = tag),
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
        assertEquals(Wire.VodMode.REMUX, vodMode(file, browser, file.audio[1]), "a stream it plays is copied, as the players start on the default")
        assertEquals(Wire.VodMode.DIRECT, media("mov,mp4,m4a,3gp,3g2,mj2", "h264", "aac").let { vodMode(it, browser, it.audio[0]) }, "picking the default changes nothing")
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

    @Test fun `HEVC tagged hev1 in MP4 is remuxed and hvc1 plays direct`() {
        assertEquals(Wire.VodMode.REMUX, vodMode(media("mov,mp4,m4a,3gp,3g2,mj2", "hevc", "aac", tag = "hev1"), tv))
        assertEquals(Wire.VodMode.DIRECT, vodMode(media("mov,mp4,m4a,3gp,3g2,mj2", "hevc", "aac", tag = "hvc1"), tv))
    }

    @Test fun `copied HEVC is written as fragmented MP4 tagged hvc1`() {
        val file = media("matroska,webm", "hevc", "eac3", 2160, "smpte2084")
        val command = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.REMUX, 0, file, null)
        assertEquals("hvc1", command[command.indexOf("-tag:v") + 1])
        assertEquals("fmp4", command[command.indexOf("-hls_segment_type") + 1])
        assertEquals("/t/s/seg%d.m4s", command[command.indexOf("-hls_segment_filename") + 1])
        val h264 = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.REMUX, 0, media("matroska,webm", "h264", "aac"), null)
        assertEquals("mpegts", h264[h264.indexOf("-hls_segment_type") + 1])
        assertFalse("-tag:v" in h264)
    }

    @Test fun `an HDR file converted for an SDR player is tone mapped`() {
        val command = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.TRANSCODE, 0, media("matroska,webm", "hevc", "eac3", 2160, "smpte2084"), null)
        assertTrue("tonemap=tonemap=hable" in command[command.indexOf("-vf") + 1])
        val sdr = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.TRANSCODE, 0, media("matroska,webm", "h264", "aac"), null)
        assertFalse("tonemap" in sdr[sdr.indexOf("-vf") + 1])
    }

    @Test fun `a hardware encoder converts and copies need no device`() {
        val vaapi = VideoEncoder(HardwareAccel.VAAPI, "/dev/dri/renderD129")
        val file = media("matroska,webm", "h264", "dts")
        val command = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.TRANSCODE, 0, file, null, encoder = vaapi)
        assertEquals("/dev/dri/renderD129", command[command.indexOf("-vaapi_device") + 1])
        assertTrue(command.indexOf("-vaapi_device") < command.indexOf("-i"))
        assertEquals("h264_vaapi", command[command.indexOf("-c:v") + 1])
        assertTrue(command[command.indexOf("-vf") + 1].endsWith("format=nv12,hwupload"))
        assertFalse("-vaapi_device" in vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.AUDIO, 0, file, null, encoder = vaapi))
        assertEquals("h264_nvenc", VideoEncoder(HardwareAccel.NVENC).outputArgs(hdr = false, keyFrameSeconds = 4).let { it[it.indexOf("-c:v") + 1] })
        assertEquals("h264_qsv", VideoEncoder(HardwareAccel.QSV).outputArgs(hdr = false, keyFrameSeconds = 4).let { it[it.indexOf("-c:v") + 1] })
    }

    @Test fun `a hardware encoder that cannot encode falls back to software`() = runTest {
        assertEquals(HardwareAccel.NONE, VideoEncoder(HardwareAccel.VAAPI).verified("ffmpeg") { false }.accel)
        assertEquals(HardwareAccel.VAAPI, VideoEncoder(HardwareAccel.VAAPI).verified("ffmpeg") { true }.accel)
        var ran = false
        assertEquals(HardwareAccel.NONE, VideoEncoder().verified("ffmpeg") { ran = true; true }.accel)
        assertFalse(ran, "software needs no check")
    }

    @Test fun `SOFA_HWACCEL takes the encoder names`() {
        assertEquals(HardwareAccel.NONE, HardwareAccel.parse(null))
        assertEquals(HardwareAccel.NONE, HardwareAccel.parse(" "))
        assertEquals(HardwareAccel.NVENC, HardwareAccel.parse("NVENC"))
        assertFailsWith<IllegalArgumentException> { HardwareAccel.parse("cuda") }
    }
}
