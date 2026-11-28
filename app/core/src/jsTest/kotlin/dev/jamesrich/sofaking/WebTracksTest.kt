package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.MediaTrackInfo
import dev.jamesrich.sofaking.data.SUBTITLE_FILE_BASE
import dev.jamesrich.sofaking.data.TrackChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebTracksTest {
    private val episode = MediaItemDetail(
        MediaItem("e1", "lib", ItemKind.EPISODE, "Pilot"),
        audio = listOf(
            MediaTrackInfo(1, "ac3", "eng", channels = 6, default = true),
            MediaTrackInfo(2, "aac", "fre", title = "Doublage", channels = 2),
        ),
        subtitles = listOf(
            MediaTrackInfo(3, "subrip", "eng"),
            MediaTrackInfo(4, "hdmv_pgs_subtitle", "fre"),
            MediaTrackInfo(SUBTITLE_FILE_BASE, "subrip", "fre"),
        ),
    )

    @Test
    fun `a file's tracks are listed by language with the file's own audio on and no subtitles`() {
        val tracks = tracksToWeb(episode, choice = null)
        assertEquals(listOf("English · AC3 5.1", "French · Doublage · AAC Stereo"), tracks.audio.map { it.label })
        assertEquals(listOf("English", "French", "French · External"), tracks.subtitles.map { it.label })
        assertEquals(listOf(false, true, false), tracks.subtitles.map { it.picture })
        assertEquals(1, tracks.audioIndex)
        assertNull(tracks.openAudio, "the file's own audio is not asked for")
        assertNull(tracks.subtitleIndex)
    }

    @Test
    fun `the languages picked in the show start its next episode with a subtitle file before a picture track`() {
        val tracks = tracksToWeb(episode, TrackChoice(audioLanguage = "fre", subtitles = true, subtitleLanguage = "fr"))
        assertEquals(2, tracks.audioIndex)
        assertEquals(2, tracks.openAudio)
        assertEquals(SUBTITLE_FILE_BASE, tracks.subtitleIndex)
    }

    @Test
    fun `a forced track is on from the start and a file with no tracks lists none`() {
        val forced = episode.copy(subtitles = episode.subtitles + MediaTrackInfo(5, "subrip", "eng", forced = true))
        assertEquals(5, tracksToWeb(forced, choice = null).subtitleIndex)
        val bare = tracksToWeb(MediaItemDetail(episode.item), choice = null)
        assertEquals(0, bare.audio.size + bare.subtitles.size)
        assertNull(bare.audioIndex)
    }
}
