package dev.jamesrich.sofaking.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize

/**
 * Match frame rate on Android TV through the window's preferred display mode, which is what makes a set change
 * its refresh rate (Media3's own `Surface.setFrameRate` asks only for seamless switches, which most TVs cannot
 * do between 60 and 59.94 Hz). The mode follows the video format the player reports and is cleared when the
 * setting goes off or the app leaves the screen.
 */
@Composable
internal fun MatchExoDisplayFrameRate(player: LivePlayer, enabled: Boolean) {
    val exo = (player as? ExoLivePlayer)?.exo ?: return
    val activity = LocalContext.current.findActivity()
    if (!enabled || activity == null) return
    var frameRate by remember(exo) { mutableStateOf(exo.videoFormat?.frameRate?.takeIf { it > 0f }) }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = update()

            override fun onTracksChanged(tracks: Tracks) = update()

            private fun update() {
                frameRate = exo.videoFormat?.frameRate?.takeIf { it > 0f }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            activity.preferDisplayMode(0)
        }
    }
    LaunchedEffect(activity, frameRate) {
        val display = activity.currentDisplay() ?: return@LaunchedEffect
        val modes = display.supportedModes.map {
            DisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
        }
        val match = matchingDisplayMode(modes, display.mode.modeId, frameRate)
        if (match != null && activity.preferDisplayMode(match.id)) player.systemPause.ignoreForModeSwitch()
    }
}

/** Asks the window for [modeId]; true when that is a change. */
private fun Activity.preferDisplayMode(modeId: Int): Boolean {
    val window = window ?: return false
    if (window.attributes.preferredDisplayModeId == modeId) return false
    window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
    return true
}

private fun Activity.currentDisplay(): Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    try {
        display
    } catch (_: UnsupportedOperationException) {
        null
    }
} else {
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
