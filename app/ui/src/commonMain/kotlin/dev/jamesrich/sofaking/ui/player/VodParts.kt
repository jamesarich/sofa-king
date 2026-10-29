package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.SubtitleLook
import dev.jamesrich.sofaking.data.VodMarker
import dev.jamesrich.sofaking.data.breakEndAt
import dev.jamesrich.sofaking.data.creditsAt
import dev.jamesrich.sofaking.data.introAt

/** How the item ends: the Up next card's count, whether it asks "Still watching?", and what plays next. */
@Stable
internal class VodEnding {
    var secondsLeft by mutableIntStateOf(UP_NEXT_SECONDS)

    /** The Up next card asks "Still watching?" and stops when it runs out, rather than playing what it names. */
    var stillWatching by mutableStateOf(false)

    /** What plays after this item, looked up once it opens, for the Next episode button. */
    var upcoming by mutableStateOf<VodUpNext?>(null)
}

/**
 * The library player's moving parts, which its effects, keys and bar share: the item's [vod] playback, the track
 * [picks], the [toasts], how it is [ending], and the surface's [focus].
 */
@Stable
internal class VodParts(
    val vod: VodSession,
    val picks: VodTrackPicks,
    val toasts: VodToasts,
    val ending: VodEnding,
    val focus: PlayerFocus,
) {
    val state: VodPlayerState get() = vod.state

    /** The info bar's height as last drawn, so a subtitle sits just above it while it shows. */
    var barHeight by mutableStateOf<Dp>(0.dp)
}

@Composable
internal fun rememberVodParts(
    player: LivePlayer,
    sessions: PlaybackSessions,
    itemId: String,
    tracks: VodTracks,
): VodParts {
    val vod = rememberVodSession(player, sessions, itemId)
    return remember(vod) {
        val toasts = VodToasts()
        VodParts(
            vod = vod,
            picks = VodTrackPicks(tracks.initialAudio, tracks.initialSubtitle, vod, toasts),
            toasts = toasts,
            ending = VodEnding(),
            focus = PlayerFocus(),
        )
    }
}

/**
 * Where the ticking position is among the item's [markers]: in a break, the intro or the credits, and past the start.
 * Read from the ticking position, so the screen recomposes only when they change.
 */
@Stable
internal class VodSpots(markers: List<VodMarker>, state: VodPlayerState) {
    val breakEnd by derivedStateOf { breakEndAt(markers, state.positionMs) }
    val intro by derivedStateOf { introAt(markers, state.positionMs) }
    val credits by derivedStateOf { creditsAt(markers, state.positionMs) }
    val pastStart by derivedStateOf { state.positionMs > START_OVER_AFTER_MS }
}

@Composable
internal fun rememberVodSpots(markers: List<VodMarker>, state: VodPlayerState): VodSpots =
    remember(markers, state) { VodSpots(markers, state) }

/**
 * How the item's captions show: a recording's own ([on], which [onToggle] switches; null for a library item, whose
 * subtitles are in its tracks) in their [style], and the text subtitles' [look].
 */
internal data class VodCaptions(
    val on: Boolean?,
    val onToggle: () -> Unit,
    val style: CaptionStyle,
    val look: SubtitleLook,
)
