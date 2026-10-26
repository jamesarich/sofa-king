package dev.jamesrich.sofaking.backend.library

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun `an audio file's tags come from its container, then its stream, with numbers before the totals`() {
        val info = parseProbe(
            """
            {"streams":[
              {"index":0,"codec_name":"alac","codec_type":"audio","channels":2,"tags":{"language":"und"}},
              {"index":1,"codec_name":"mjpeg","codec_type":"video","disposition":{"attached_pic":1}}
            ],"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"241.5","tags":{
              "title":"Song","ARTIST":"Guest","albumartist":"Band","album":"Record","track":"03/12","disc":"2/2",
              "date":"1999-04-01","genre":"Rock; Pop","compilation":"0","REPLAYGAIN_TRACK_GAIN":"-6.54 dB"}}}
            """.trimIndent(),
        )!!
        assertNull(info.video)
        assertTrue(info.picture)
        val tags = info.tags!!
        assertEquals("Song", tags.title)
        assertEquals("Guest", tags.artist)
        assertEquals("Band", tags.albumArtist)
        assertEquals(3, tags.track)
        assertEquals(2, tags.disc)
        assertEquals("1999-04-01", tags.date)
        assertEquals(listOf("Rock", "Pop"), tags.genres)
        assertFalse(tags.compilation)
        assertEquals(-6.54, tags.trackGain!!, 0.001)
    }

    @Test fun `a video's tags are not read as music`() {
        val info = parseProbe(
            """{"streams":[{"index":0,"codec_name":"h264","codec_type":"video"}],
            "format":{"format_name":"mp4","tags":{"title":"Holiday","artist":"Me"}}}""",
        )!!
        assertNull(info.tags)
    }

    @Test fun `FFprobe reads an Opus file's tags from its stream`() = runBlocking {
        val dir = Files.createTempDirectory("sofa-probe-")
        val file = dir.resolve("song.opus")
        val made = ProcessBuilder(
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=d=1", "-metadata", "title=Song",
            "-metadata", "album_artist=Band", "-metadata", "album=Record", "-metadata", "track=3/12", file.toString(),
        ).inheritIO().start().waitFor()
        assertEquals(0, made)
        val tags = MediaProbe("ffprobe").probe(file)?.tags
        assertEquals(AudioTags(title = "Song", albumArtist = "Band", album = "Record", track = 3), tags)
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
