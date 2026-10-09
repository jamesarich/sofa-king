package dev.jamesrich.sofaking.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import dev.jamesrich.sofaking.data.CaptionStyle

/**
 * Live video through media3-ui-compose: `ContentFrame` sizes a SurfaceView to the picture and shows
 * [shutter] from a reset until the next first frame, which is what each zap's `stop()` produces.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun LiveVideo(player: Player, modifier: Modifier = Modifier, shutter: @Composable () -> Unit) {
    ContentFrame(
        player = player,
        modifier = modifier,
        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
        contentScale = ContentScale.Fit,
        shutter = shutter,
    )
}

/** Hands the player's cues to a [SubtitleView]; media3-ui-compose draws no captions of its own yet. */
private class CueBridge : Player.Listener {
    var view: SubtitleView? = null

    override fun onCues(cueGroup: CueGroup) {
        view?.setCues(cueGroup.cues)
    }
}

/** Captions over the video, styled by [style] and kept above the bottom overscan by [bottomFraction]. */
@OptIn(UnstableApi::class)
@Composable
internal fun CaptionLayer(player: Player, style: CaptionStyle, bottomFraction: Float, modifier: Modifier = Modifier) {
    val bridge = remember(player) { CueBridge() }
    DisposableEffect(player) {
        player.addListener(bridge)
        onDispose { player.removeListener(bridge) }
    }
    // Remembers the style already applied, so recompositions don't restyle the subtitles.
    val appliedStyle = remember { arrayOfNulls<CaptionStyle>(1) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SubtitleView(context).also { view ->
                bridge.view = view
                view.setCues(player.currentCues.cues)
            }
        },
        update = { view ->
            if (appliedStyle[0] != style) {
                applyCaptionAppearance(view, style, bottomFraction)
                appliedStyle[0] = style
            }
        },
        onRelease = { bridge.view = null },
    )
}

/** Holds the screen on through the host view while [on]; cleared when this leaves composition. */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
