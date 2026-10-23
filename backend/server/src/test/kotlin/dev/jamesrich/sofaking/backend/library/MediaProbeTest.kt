package dev.jamesrich.sofaking.backend.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaProbeTest {
    @Test fun `ffprobe json reads container, duration, streams and HDR`() {
        val info = parseProbe(
            """
            {"streams":[
              {"index":0,"codec_name":"hevc","codec_type":"video","profile":"Main 10","level":150,"width":3840,"height":2160,"pix_fmt":"yuv420p10le","avg_frame_rate":"24000/1001","color_transfer":"smpte2084"},
              {"index":1,"codec_name":"truehd","codec_type":"audio","channels":8,"tags":{"language":"eng"},"disposition":{"default":1}},
              {"index":2,"codec_name":"ac3","codec_type":"audio","channels":6,"tags":{"language":"eng","title":"Commentary"},"disposition":{"default":0}},
              {"index":3,"codec_name":"subrip","codec_type":"subtitle","tags":{"language":"eng"},"disposition":{"forced":1}},
              {"index":4,"codec_name":"mjpeg","codec_type":"video","disposition":{"attached_pic":1}}
            ],"format":{"format_name":"matroska,webm","duration":"7020.500000","bit_rate":"45000000"}}
            """.trimIndent(),
        )!!
        assertEquals("matroska,webm", info.container)
        assertEquals(7_020_500, info.durationMs)
        assertEquals("hevc", info.video?.codec)
        assertEquals(2160, info.video?.height)
        assertEquals("smpte2084", info.video?.transfer)
        assertEquals(23.976, info.video!!.frameRate!!, 0.001)
        assertEquals(listOf("truehd", "ac3"), info.audio.map { it.codec })
        assertTrue(info.audio.first().default)
        assertTrue(info.subtitles.single().forced)
    }

    @Test fun `no streams or junk is no media`() {
        assertNull(parseProbe("""{"streams":[],"format":{"format_name":"mp4"}}"""))
        assertNull(parseProbe("not json"))
    }

    @Test fun `ffprobe sits beside a configured ffmpeg`() {
        assertEquals("ffprobe", ffprobeBeside("ffmpeg"))
        assertEquals("/opt/ffmpeg/bin/ffprobe", ffprobeBeside("/opt/ffmpeg/bin/ffmpeg"))
    }
}
