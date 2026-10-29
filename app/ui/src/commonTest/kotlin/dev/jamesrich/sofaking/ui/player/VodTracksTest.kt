package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.SUBTITLE_OFFSET_MAX_MS
import dev.jamesrich.sofaking.data.stepSubtitleOffset
import dev.jamesrich.sofaking.data.subtitleOffsetText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VodTracksTest {
    private val english = VodTrack(3, "English")
    private val spanish = VodTrack(4, "Spanish")

    @Test
    fun `with one audio track the card opens on the subtitle playing`() {
        val tracks = VodTracks(audio = listOf(VodTrack(1, "English")), subtitles = listOf(english, spanish))
        assertEquals(TracksFocus.Subtitle(4), tracksFocus(tracks, audio = 1, subtitle = 4))
        assertEquals(TracksFocus.Subtitle(null), tracksFocus(tracks, audio = 1, subtitle = null))
    }

    @Test
    fun `a subtitle the item no longer lists opens on Off`() {
        val tracks = VodTracks(subtitles = listOf(english))
        assertEquals(TracksFocus.Subtitle(null), tracksFocus(tracks, audio = null, subtitle = 9))
    }

    @Test
    fun `with a choice of audio the card opens on the audio playing or the first`() {
        val tracks =
            VodTracks(audio = listOf(VodTrack(1, "English"), VodTrack(2, "Commentary")), subtitles = listOf(english))
        assertEquals(TracksFocus.Audio(2), tracksFocus(tracks, audio = 2, subtitle = 3))
        assertEquals(TracksFocus.Audio(1), tracksFocus(tracks, audio = null, subtitle = 3))
    }

    @Test
    fun `the CC key turns on the subtitle shown last or the starting one or the first text track`() {
        val picture = VodTrack(2, "English (PGS)", picture = true)
        val tracks = VodTracks(subtitles = listOf(picture, english, spanish), initialSubtitle = 4)
        assertEquals(3, tracks.subtitleToTurnOn(last = 3))
        assertEquals(4, tracks.subtitleToTurnOn(last = null))
        assertEquals(4, tracks.subtitleToTurnOn(last = 9), "a track the item no longer lists")
        assertEquals(3, VodTracks(subtitles = listOf(picture, english)).subtitleToTurnOn(last = null))
        assertEquals(2, VodTracks(subtitles = listOf(picture)).subtitleToTurnOn(last = null))
        assertNull(VodTracks().subtitleToTurnOn(last = null))
    }

    @Test
    fun `subtitle timing moves a quarter second a press up to ten seconds either way`() {
        assertEquals(250L, stepSubtitleOffset(0, 1))
        assertEquals(-500L, stepSubtitleOffset(-250, -1))
        assertEquals(SUBTITLE_OFFSET_MAX_MS, stepSubtitleOffset(SUBTITLE_OFFSET_MAX_MS, 1))
        assertEquals(-SUBTITLE_OFFSET_MAX_MS, stepSubtitleOffset(-9_900, -1))
        assertEquals(
            listOf("0 s", "+0.25 s", "\u22121.5 s", "+10 s"),
            listOf(0L, 250L, -1_500L, 10_000L).map(::subtitleOffsetText),
        )
    }
}
