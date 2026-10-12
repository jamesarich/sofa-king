package dev.jamesrich.sofaking.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW

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

/** Holds the screen on through the host view while [on]; cleared when this leaves composition. */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
