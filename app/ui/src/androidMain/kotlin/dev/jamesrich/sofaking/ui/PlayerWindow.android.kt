package dev.jamesrich.sofaking.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.pip.PictureInPictureDelegate
import androidx.core.pip.VideoPlaybackPictureInPicture
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * Hides the system bars while a player is on top, and on a phone or tablet hands the player to Jetpack's core-pip,
 * which shrinks it into picture-in-picture when the viewer leaves the app: by itself from Android 12, on the leave
 * hint before. A TV is left out; its remote's Home leaves the app, which stops the stream. The window takes the
 * video's shape, and the system's picture-in-picture menu takes play and pause from the player's media session.
 * Closing the window stops the activity, and the app's ON_STOP stops the stream as it does behind another app.
 */
@Composable
internal actual fun rememberPlayerWindow(player: LivePlayer, active: Boolean): Boolean {
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return false
    HideSystemBars(activity, active)
    return rememberPictureInPicture(activity, player, active)
}

/** Immersive while [hidden]: the bars come back for a moment on a swipe from the edge, and for good when it ends. */
@Composable
private fun HideSystemBars(activity: Activity, hidden: Boolean) {
    DisposableEffect(activity, hidden) {
        if (!hidden) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun rememberPictureInPicture(activity: ComponentActivity, player: LivePlayer, enabled: Boolean): Boolean {
    val supported = remember(activity) {
        !activity.isTelevision() && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    if (!supported) return false
    val view = LocalView.current
    var inPictureInPicture by remember(activity) { mutableStateOf(activity.isInPictureInPictureMode) }
    // One per activity: the delegate keeps its listeners on the activity for as long as the activity lives.
    val pip = remember(activity) { VideoPlaybackPictureInPicture(activity, Dispatchers.Default.asExecutor()) }
    DisposableEffect(pip) {
        val listener = object : PictureInPictureDelegate.OnPictureInPictureEventListener {
            override fun onPictureInPictureEvent(event: PictureInPictureDelegate.Event, config: Configuration?) {
                when (event) {
                    // Android 15 says when the window starts to shrink, so the overlays go before it does.
                    PictureInPictureDelegate.Event.ENTER_ANIMATION_START, PictureInPictureDelegate.Event.ENTERED ->
                        inPictureInPicture =
                            true

                    PictureInPictureDelegate.Event.EXITED -> inPictureInPicture = false
                }
            }
        }
        pip.addOnPictureInPictureEventListener(ContextCompat.getMainExecutor(activity), listener)
        onDispose {
            pip.removeOnPictureInPictureEventListener(listener)
            pip.close()
        }
    }
    val aspect = rememberVideoAspect(player)
    // The player fills the app's view, which the delegate tracks for the shrink animation, cropped to the video.
    DisposableEffect(pip, enabled, aspect) {
        pip.setPlayerView(view.takeIf { enabled }).setEnabled(enabled)
        aspect?.let(pip::setAspectRatio)
        pip.commit()
        onDispose {}
    }
    return inPictureInPicture
}

/**
 * The shape of the picture the player shows, or null before it knows; core-pip keeps it within what the system allows.
 */
@Composable
private fun rememberVideoAspect(player: LivePlayer): Rational? {
    val size by player.videoSize.collectAsState()
    return size?.takeIf { it.width > 0 && it.height > 0 }?.let { Rational(it.width, it.height) }
}
