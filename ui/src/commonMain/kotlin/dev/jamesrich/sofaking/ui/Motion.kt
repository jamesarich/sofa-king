package dev.jamesrich.sofaking.ui

import android.os.SystemClock
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay

/** Small things (pills, badges, the header loader) pop in on a soft bounce and fade out. */
val PopIn: EnterTransition = fadeIn(tween(120)) + scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow), initialScale = 0.8f)
val PopOut: ExitTransition = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.9f)

/** Panels rise from the bottom edge on a spatial spring and sink a little as they fade. */
val RiseIn: EnterTransition = fadeIn(tween(160)) +
    slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow, IntOffset.VisibilityThreshold)) { it / 4 }
val SinkOut: ExitTransition = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 8 }

/**
 * [target], but shown only once it has held for [showAfterMs] and then kept for at least [holdMs], so a
 * state that flickers (a live stream's brief rebuffers) neither flashes nor restarts its animation.
 */
@Composable
fun rememberSettled(target: Boolean, showAfterMs: Long = 250, holdMs: Long = 700): Boolean {
    var shown by remember { mutableStateOf(false) }
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(target) {
        if (target && !shown) {
            delay(showAfterMs)
            shown = true
            shownAt = SystemClock.uptimeMillis()
        } else if (!target && shown) {
            delay((holdMs - (SystemClock.uptimeMillis() - shownAt)).coerceAtLeast(0))
            shown = false
        }
    }
    return shown
}
