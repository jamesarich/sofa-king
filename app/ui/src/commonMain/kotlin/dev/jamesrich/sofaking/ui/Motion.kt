package dev.jamesrich.sofaking.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Small things (pills, badges, the header loader) pop in on the theme's fast spatial spring and fade out. */
val PopIn: EnterTransition
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.motionScheme.let {
        fadeIn(it.fastEffectsSpec()) + scaleIn(it.fastSpatialSpec(), initialScale = 0.8f)
    }
val PopOut: ExitTransition
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.motionScheme.let {
        fadeOut(it.fastEffectsSpec()) + scaleOut(it.fastEffectsSpec(), targetScale = 0.9f)
    }

/** Panels rise from the bottom edge on the theme's spatial spring and sink a little as they fade. */
val RiseIn: EnterTransition
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.motionScheme.let {
        fadeIn(it.defaultEffectsSpec()) + slideInVertically(it.defaultSpatialSpec()) { h -> h / 4 }
    }
val SinkOut: ExitTransition
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.motionScheme.let {
        fadeOut(it.fastEffectsSpec()) + slideOutVertically(it.fastEffectsSpec()) { h -> h / 8 }
    }

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
            shownAt = uptimeMs()
        } else if (!target && shown) {
            delay((holdMs - (uptimeMs() - shownAt)).coerceAtLeast(0))
            shown = false
        }
    }
    return shown
}
