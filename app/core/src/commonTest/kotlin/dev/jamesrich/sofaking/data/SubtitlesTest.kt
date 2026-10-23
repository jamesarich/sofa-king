package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitlesTest {
    @Test
    fun `WebVTT cues read with short and long times and lose their markup`() {
        val subtitles = parseWebVtt(
            "WEBVTT\r\n\r\nNOTE made by FFmpeg\r\n\r\n00:01.000 --> 00:03.000\r\nHello <i>there</i>\r\n\r\n" +
                "intro\n01:00:04.500 --> 01:00:06.000 align:start\nTom &amp; Jerry\nsecond line\n",
        )
        assertEquals(
            listOf(
                SubtitleCue(1_000, 3_000, "Hello there"),
                SubtitleCue(3_604_500, 3_606_000, "Tom & Jerry\nsecond line"),
            ),
            subtitles.cues,
        )
    }

    /** What FFmpeg 6.1, the server's, writes for an SRT file whose rows carry `<br>`. */
    @Test
    fun `a break FFmpeg wrote as a blank line keeps the rest of its cue`() {
        val subtitles = parseWebVtt(
            "WEBVTT\n\n00:01.000 --> 00:03.000\nlead\n\n\ntrail\n\n" +
                "00:04.000 --> 00:06.000\n\nx y\nz\n\nNOTE between\n\n00:07.000 --> 00:08.000\nlast\n",
        )
        assertEquals(
            listOf(
                SubtitleCue(1_000, 3_000, "lead\ntrail"),
                SubtitleCue(4_000, 6_000, "x y\nz"),
                SubtitleCue(7_000, 8_000, "last"),
            ),
            subtitles.cues,
        )
    }

    @Test
    fun `a break tag is a new line even without its bracket or escaped`() {
        val subtitles = parseWebVtt(
            "WEBVTT\n\n00:01.000 --> 00:02.000\none<br\ntwo <BR/> three\n\n" +
                "00:03.000 --> 00:04.000\nfour &lt;br&gt; five <br six\n\n00:05.000 --> 00:06.000\na < b <i>and</i> " +
                "c > d <00:05.500>e\n",
        )
        assertEquals(listOf("one\ntwo\nthree", "four\nfive\nsix", "a < b and c > d e"), subtitles.cues.map { it.text })
    }

    /** Web subtitles converted from TTML often escape their tags; FFmpeg passes the escapes through. */
    @Test
    fun `escaped tags are stripped and angle brackets that open no tag stay`() {
        val subtitles = parseWebVtt(
            "WEBVTT\n\n00:01.000 --> 00:02.000\n&lt;i&gt;Previously&lt;/i&gt; &amp;lt;3\n\n" +
                "00:03.000 --> 00:04.000\n<<Previously>> x<y <c.yellow>vtt</c>\n",
        )
        assertEquals(listOf("Previously &lt;3", "<<Previously>> x<y vtt"), subtitles.cues.map { it.text })
    }

    /** Lines from an embedded SubRip track that was cut at a fixed length mid-tag, as FFmpeg 6.1 passes them on. */
    @Test
    fun `a tag cut off at the end of a line is dropped with the text before it kept`() {
        val lines = listOf(
            "Woman:<br />hello, you've reach.",
            "For comments and complaints,<br.",
            "Stupid with<.",
            "Did ancient aliens really help<?",
            "dinosaurs, one of the most<br /s",
            "friend<bs",
            "<i>maya. Ma></i>",
            "I <3 it <i>so</i>",
            "just named it that.Br />fertile.",
            "Yeah, that's what it is.R />we .",
            "Yeah, huh. />I can't read that .",
            "-Yeah, no...Pun intended.\n.",
            "♪ ♪\n-t.",
            "You had situations with her?R /.",
        )
        val document =
            "WEBVTT\n\n" +
                lines.mapIndexed { i, line ->
                    "00:${i.toString().padStart(2, '0')}.000 --> 00:${i.toString().padStart(2, '0')}.900\n$line"
                }.joinToString("\n\n")
        assertEquals(
            listOf(
                "Woman:\nhello, you've reach.", "For comments and complaints,", "Stupid with",
                "Did ancient aliens really help",
                "dinosaurs, one of the most", "friend", "maya. Ma", "I <3 it so",
                "just named it that.\nfertile.", "Yeah, that's what it is.\nwe .", "Yeah, huh.\nI can't read that .",
                "-Yeah, no...Pun intended.", "♪ ♪\n-t.", "You had situations with her?",
            ),
            parseWebVtt(document).cues.map { it.text },
        )
    }

    @Test
    fun `the text at a position is every cue showing then`() {
        val subtitles =
            Subtitles(
                listOf(
                    SubtitleCue(5_000, 9_000, "b"),
                    SubtitleCue(1_000, 6_000, "a"),
                    SubtitleCue(10_000, 11_000, "c"),
                ),
            )
        assertNull(subtitles.textAt(500))
        assertEquals("a", subtitles.textAt(1_000))
        assertEquals("a\nb", subtitles.textAt(5_500))
        assertEquals("b", subtitles.textAt(6_000), "a cue ends at its end time")
        assertNull(subtitles.textAt(9_500))
        assertEquals("c", subtitles.textAt(10_999))
    }

    @Test
    fun `times that are not WebVTT read as nothing`() {
        assertEquals(61_250, vttTime("01:01.25"))
        assertNull(vttTime("61"))
        assertNull(vttTime("aa:bb.ccc"))
    }
}
