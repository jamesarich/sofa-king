package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.formatNumber
import dev.jamesrich.sofaking.ui.rememberMotionEnabled
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.delay

/** The picture stays black after a zap for at most this long, so a slow tune shows the buffering pill instead. */
internal const val ZAP_DIP_MAX_MS = 400L

/** The black fades out over this long once the new channel plays or the hold ends. */
internal const val ZAP_DIP_FADE_MS = 150

/** How long the new channel's number stays large after a zap. */
internal const val ZAP_NUMBER_MS = 2_000L

/**
 * Whether a zap dips the picture: not with animations off, not over a failure card, and not over the break screen,
 * which covers the picture itself.
 */
internal fun zapDipAllowed(motionEnabled: Boolean, failed: Boolean, breakScreenUp: Boolean): Boolean =
    motionEnabled && !failed && !breakScreenUp

/** Whether the black still holds [sinceZapMs] after a zap: until the new stream plays, but never past the cap. */
internal fun zapDipHolds(sinceZapMs: Long, newStreamPlaying: Boolean): Boolean =
    !newStreamPlaying && sinceZapMs < ZAP_DIP_MAX_MS

/** Whether the zap number shows: typed digits and the miss notice take its corner. */
internal fun zapNumberShown(withinWindow: Boolean, typing: Boolean): Boolean = withinWindow && !typing

/**
 * A black scrim over the picture on each channel change ([zaps] counts them): it snaps to black, holds as
 * [zapDipHolds] says, then fades out. A zap while [allowed] is false, or one that was already counted when the
 * screen composed, leaves the picture alone. The cards and pills draw above it.
 */
@Composable
internal fun ZapDip(zaps: Int, allowed: Boolean, newStreamPlaying: () -> Boolean) {
    val alpha = remember { Animatable(0f) }
    var handled by remember { mutableIntStateOf(zaps) }
    val playing by rememberUpdatedState(newStreamPlaying)
    LaunchedEffect(zaps, allowed) {
        if (!allowed) {
            alpha.snapTo(0f)
            handled = zaps
            return@LaunchedEffect
        }
        if (zaps == handled) return@LaunchedEffect
        handled = zaps
        alpha.snapTo(1f)
        val start = uptimeMs()
        while (zapDipHolds(uptimeMs() - start, playing())) withFrameNanos {}
        alpha.animateTo(0f, tween(ZAP_DIP_FADE_MS))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.Black))
}

/** The [ZapDip] for the live player's channel changes; [playing] says whether the stream plays now. */
@Composable
internal fun LiveZapDip(state: PlayerState, channel: Channel, breakScreen: Boolean, playing: () -> Boolean) {
    val allowed = zapDipAllowed(rememberMotionEnabled(), failed = state.failure != null, breakScreenUp = breakScreen)
    ZapDip(state.zaps, allowed) { state.tuned?.guideId == channel.guideId && playing() }
}

/** The [ZapNumber] for [channel], which typed digits and their miss notice replace. */
@Composable
internal fun BoxScope.LiveZapNumber(state: PlayerState, channel: Channel) {
    ZapNumber(state.zaps, channel.number.formatNumber(), typing = state.numbers.hasDigits || state.numbers.miss != null)
}

/**
 * The new channel's [number], large in the top corner for [ZAP_NUMBER_MS] after each zap; [typing] hides it while
 * digits or the miss notice use the corner. The bar names the channel to a screen reader, so the number has no
 * semantics of its own.
 */
@Composable
internal fun BoxScope.ZapNumber(zaps: Int, number: String, typing: Boolean) {
    var withinWindow by remember { mutableStateOf(false) }
    var handled by remember { mutableIntStateOf(zaps) }
    LaunchedEffect(zaps) {
        if (zaps == handled) return@LaunchedEffect
        handled = zaps
        withinWindow = true
        delay(ZAP_NUMBER_MS)
        withinWindow = false
    }
    AnimatedVisibility(
        zapNumberShown(withinWindow, typing),
        modifier = Modifier.align(Alignment.TopEnd),
        enter = PopIn,
        exit = PopOut,
    ) {
        Text(
            number,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .screenPadding()
                .background(SofaOverlayScrim, MaterialTheme.shapes.small)
                .padding(horizontal = 24.dp)
                .clearAndSetSemantics {},
        )
    }
}
