package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.captions_off
import dev.jamesrich.sofaking.resources.captions_on
import dev.jamesrich.sofaking.resources.vod_no_subtitles
import dev.jamesrich.sofaking.resources.vod_subtitles_off
import dev.jamesrich.sofaking.resources.vod_subtitles_on

/**
 * The audio and subtitle tracks picked for the item, the text subtitles' cues and timing, and the picks that change
 * them. A text subtitle is drawn here over the same stream; a picture one, on or off, needs a new stream.
 */
@Stable
internal class VodTrackPicks(
    initialAudio: Int?,
    initialSubtitle: Int?,
    private val vod: VodSession,
    private val toasts: VodToasts,
) {
    var audio by mutableStateOf(initialAudio)
        private set
    var subtitle by mutableStateOf(initialSubtitle)
        private set

    /** How much later than the file says the text subtitles show (earlier when negative); each item starts at 0. */
    var subtitleOffsetMs by mutableLongStateOf(0L)

    /** The picked text subtitle's cues, once read. */
    var subtitles by mutableStateOf<Subtitles?>(null)
        private set

    /** The subtitle track CC turns back on. */
    private var lastSubtitle by mutableStateOf(initialSubtitle)

    fun pickAudio(index: Int, tracks: VodTracks) {
        vod.state.closeTracks()
        if (index == audio) return
        audio = index
        tracks.onPicked(audio, subtitle)
        reopenWithTracks(tracks)
    }

    fun pickSubtitle(index: Int?, tracks: VodTracks) {
        vod.state.closeTracks()
        if (index == subtitle) return
        if (index != null) lastSubtitle = index
        val restream = tracks.isPicture(index) || tracks.isPicture(subtitle)
        subtitle = index
        tracks.onPicked(audio, subtitle)
        if (restream) reopenWithTracks(tracks)
    }

    /** Plays the item again from here with the picked audio and, for a picture subtitle, that subtitle drawn in. */
    private fun reopenWithTracks(tracks: VodTracks) {
        val audio = audio
        val picture = subtitle?.takeIf(tracks::isPicture)
        vod.restream("track switch") { tracks.select(audio, picture, vod.state.positionMs) }
    }

    /**
     * CC and C: a recording's captions on or off ([captionsOn], which [onToggleCaptions] switches; null for a library
     * item); a library item's subtitles off, or back on with the last ones shown.
     */
    fun switchCaptions(captionsOn: Boolean?, onToggleCaptions: () -> Unit, tracks: VodTracks) {
        if (captionsOn != null) {
            onToggleCaptions()
            toasts.toast = VodToast(if (captionsOn) Res.string.captions_off else Res.string.captions_on)
            return
        }
        if (subtitle != null) {
            pickSubtitle(null, tracks)
            toasts.toast = VodToast(Res.string.vod_subtitles_off)
            return
        }
        val target = tracks.subtitleToTurnOn(lastSubtitle)
        if (target == null) {
            toasts.toast = VodToast(Res.string.vod_no_subtitles)
        } else {
            pickSubtitle(target, tracks)
            toasts.toast = VodToast(Res.string.vod_subtitles_on)
        }
    }

    /** Reads the picked subtitle track's cues, once per pick; a track the server cannot give turns them off. */
    suspend fun loadSubtitles(tracks: VodTracks) {
        subtitles = null
        val index = subtitle?.takeUnless(tracks::isPicture) ?: return
        subtitles = tracks.loadSubtitles(index)
        if (subtitles == null) {
            subtitle = null
            toasts.subtitlesFailed = true
        }
    }
}
