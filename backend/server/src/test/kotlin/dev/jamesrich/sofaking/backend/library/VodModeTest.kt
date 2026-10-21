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

    @Test fun `video with more than 8 bits a sample plays as is only on a player that decodes it`() {
        fun deep(video: String, format: String = "yuv420p10le") = media("matroska,webm", video, "aac").let { it.copy(video = it.video!!.copy(pixelFormat = format)) }
        val main10 = tv.copy(deepColor = listOf("hevc"))
        assertEquals(Wire.VodMode.DIRECT, vodMode(deep("hevc"), main10))
        assertEquals(Wire.VodMode.TRANSCODE, vodMode(deep("hevc"), tv.copy(deepColor = emptyList())), "a decoder without Main 10")
        assertEquals(Wire.VodMode.TRANSCODE, vodMode(deep("h264"), main10), "H.264 High 10")
        assertEquals(Wire.VodMode.DIRECT, vodMode(deep("h264", "yuv420p"), main10), "8-bit")
        // A player that does not say decodes them all but H.264, as players did before they could say.
        assertEquals(Wire.VodMode.DIRECT, vodMode(deep("hevc", "p010le"), tv))
        assertEquals(Wire.VodMode.TRANSCODE, vodMode(deep("h264"), tv))
        assertTrue(deepColor(VideoStream("hevc", bitDepth = 10)))
        assertFalse(deepColor(VideoStream("h264", pixelFormat = "yuvj420p")))
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

    @Test fun `a picture subtitle is drawn over the converted picture at the picture's size`() {
        val file = media("matroska,webm", "hevc", "eac3", 2160, "smpte2084").let { it.copy(video = it.video!!.copy(width = 3840)) }
        val embedded = SubtitleSource(SubtitleStream(4, "hdmv_pgs_subtitle"), null)
        val command = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.TRANSCODE, 5_000, file, null, burn = embedded)
        val graph = command[command.indexOf("-filter_complex") + 1]
        assertTrue(graph.startsWith("[0:v:0]zscale") && "[0:4]scale=3840:2160[sub]" in graph && "[base][sub]overlay" in graph, graph)
        assertEquals("[v]", command[command.indexOf("-map") + 1], "the drawn picture is the output's video")
        assertFalse("-vf" in command)
        assertEquals(1, command.count { it == "-i" })

        val sidecar = SubtitleSource(SubtitleStream(1000, "hdmv_pgs_subtitle"), Path.of("/m/a.en.sup"))
        val withFile = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.TRANSCODE, 5_000, media("matroska,webm", "h264", "aac"), null, burn = sidecar)
        val second = withFile.indexOf("/m/a.en.sup")
        assertEquals("-i", withFile[second - 1])
        assertEquals("5.000", withFile[second - 2], "the subtitle file seeks with the video")
        val graph2 = withFile[withFile.indexOf("-filter_complex") + 1]
        assertTrue(graph2.startsWith("[0:v:0]null[base];[1:s:0]null[sub]"), graph2)
    }

    @Test fun `a whole playlist lists every segment and FFmpeg numbers and times them from where it starts`() {
        val playlist = wholePlaylist(9_500)
        assertEquals(listOf("seg0.ts", "seg1.ts", "seg2.ts"), playlist.lines().filter { it.endsWith(".ts") })
        assertTrue("#EXTINF:4.000,\nseg0.ts" in playlist && "#EXTINF:1.500,\nseg2.ts" in playlist && playlist.endsWith("#EXT-X-ENDLIST\n"), playlist)
        assertTrue("#EXT-X-PLAYLIST-TYPE:VOD" in playlist)
        assertEquals(2, segmentCount(8_000))
        assertEquals(2, segmentCount(8_021), "a sliver past the last segment joins it")
        assertTrue("#EXTINF:4.021,\nseg1.ts" in wholePlaylist(8_021))
        assertEquals(1, segmentCount(300))

        val command = vodCommand("ffmpeg", Path.of("/m/a.mkv"), Path.of("/t/s"), Wire.VodMode.TRANSCODE, 12_000, media("matroska,webm", "h264", "aac"), null, firstSegment = 3)
        assertEquals("12.000", command[command.indexOf("-ss") + 1])
        assertEquals("12.000", command[command.indexOf("-output_ts_offset") + 1])
        assertEquals("3", command[command.indexOf("-start_number") + 1])
        assertEquals("/t/s/ffmpeg.m3u8", command.last(), "the server's own playlist stays as written")
        assertFalse("-avoid_negative_ts" in command)
    }

    @Test fun `a hardware encoder that cannot encode falls back to software`() = runTest {
        assertEquals(HardwareAccel.NONE, VideoEncoder(HardwareAccel.VAAPI).verified("ffmpeg") { false }.accel)
        assertEquals(HardwareAccel.VAAPI, VideoEncoder(HardwareAccel.VAAPI).verified("ffmpeg") { true }.accel)
        var ran = false
        assertEquals(HardwareAccel.NONE, VideoEncoder().verified("ffmpeg") { ran = true; true }.accel)
        assertFalse(ran, "software needs no check")
    }

    @Test fun `auto takes the first GPU encoder that encodes, and software when none does`() = runTest {
        val tried = mutableListOf<String>()
        fun encoderOf(command: List<String>) = command[command.indexOf("-c:v") + 1]
        val devices = setOf(VideoEncoder.NVIDIA_DEVICE, VideoEncoder.DEFAULT_DEVICE)
        val picked = VideoEncoder(HardwareAccel.AUTO).verified("ffmpeg", exists = { it in devices }) { command ->
            tried += encoderOf(command); encoderOf(command) == "h264_vaapi"
        }
        assertEquals(HardwareAccel.VAAPI, picked.accel)
        assertEquals(listOf("h264_nvenc", "h264_vaapi"), tried, "NVENC first, then VA-API, stopping at the first that works")

        tried.clear()
        assertEquals(HardwareAccel.NONE, VideoEncoder(HardwareAccel.AUTO).verified("ffmpeg", exists = { false }) { tried += encoderOf(it); true }.accel)
        assertEquals(emptyList(), tried, "no device, no FFmpeg run")
        assertEquals(HardwareAccel.NONE, VideoEncoder(HardwareAccel.AUTO).verified("ffmpeg", exists = { it == VideoEncoder.DEFAULT_DEVICE }) { false }.accel)
        assertEquals("libx264", VideoEncoder(HardwareAccel.AUTO).codecArgs(4)[1], "unverified, auto encodes in software")
        assertEquals(HardwareAccel.AUTO, HardwareAccel.parse("auto"))
    }

    @Test fun `SOFA_HWACCEL takes the encoder names`() {
        assertEquals(HardwareAccel.NONE, HardwareAccel.parse(null))
        assertEquals(HardwareAccel.NONE, HardwareAccel.parse(" "))
        assertEquals(HardwareAccel.NVENC, HardwareAccel.parse("NVENC"))
        assertFailsWith<IllegalArgumentException> { HardwareAccel.parse("cuda") }
    }
}
