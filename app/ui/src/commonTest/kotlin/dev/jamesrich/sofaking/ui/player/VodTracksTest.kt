package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

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
        val tracks = VodTracks(audio = listOf(VodTrack(1, "English"), VodTrack(2, "Commentary")), subtitles = listOf(english))
        assertEquals(TracksFocus.Audio(2), tracksFocus(tracks, audio = 2, subtitle = 3))
        assertEquals(TracksFocus.Audio(1), tracksFocus(tracks, audio = null, subtitle = 3))
    }
}
