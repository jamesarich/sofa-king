package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.preview.PreviewDwell
import dev.jamesrich.sofaking.ui.rememberScreenReaderActive
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Clock

/**
 * What the preview's rest timers watch: the channel under focus, the last key press, whether the preview
 * failed and whether sound is on.
 */
private data class DwellInput(val channelId: String?, val nonce: Int, val failed: Boolean, val sound: Boolean)

/**
 * The preview's rest timers: muted on tune, sound after five seconds, full screen after [autoOpenDelay], restarted by
 * any key. [returnedFrom], the channel the full screen was just left on, does not reopen until focus has moved.
 */
@Composable
internal fun rememberPreviewDwell(autoOpenDelay: AutoOpenDelay, returnedFrom: String?): PreviewDwell {
    // TalkBack reads the grid aloud as focus moves; a channel opening full screen would cut that off.
    val touchExploration = rememberScreenReaderActive()
    val fullScreenAfterMs = autoOpenDelay.afterMs.takeUnless { touchExploration }
    val lastInput = LocalLastInput.current
    return remember(fullScreenAfterMs) {
        PreviewDwell(
            suppressedChannelId = returnedFrom,
            fullScreenAfterMs = fullScreenAfterMs,
            pointerDriven = lastInput.kind == InputKind.POINTER,
        )
    }
}

/**
 * Runs [dwell]'s timers. Both run only while a cell holds focus with search closed, so typing never opens a channel;
 * when the full screen is due, [open] takes the channel as unattended. Observed from the snapshot, not read in
 * composition, so a focus move still recomposes only the band. Nothing opens while the app is stopped (Home, the
 * speech recognizer, screen off); coming back starts the rest over, muted, so a channel is never found open on
 * return.
 */
@Composable
internal fun PreviewRestEffects(
    state: GuideState,
    nav: GuideNavigator,
    channels: GuideChannels,
    dwell: PreviewDwell,
    sound: Boolean,
    open: (Channel, Boolean) -> Unit,
) {
    val lifecycle = appLifecycleOwner.lifecycle
    LifecycleEventEffect(Lifecycle.Event.ON_START, appLifecycleOwner) {
        dwell.restart(uptimeMs())
        state.dwellNonce++
        // Back after half an hour or more, the grid shows now again: the window rolls on if it must and the
        // focused row jumps to the program on now.
        val at = Clock.System.now()
        if (state.cameBack(at)) {
            state.rollWindow(at)
            val guideId = nav.anchorGuideId(channels.items)
            if (guideId != null) nav.requestJump(guideId, GuideTimeline.offsetMinutes(state.liveStart, at))
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, appLifecycleOwner) { state.left(Clock.System.now()) }
    val currentSound by rememberUpdatedState(sound)
    val currentOpen by rememberUpdatedState(open)
    LaunchedEffect(dwell) {
        snapshotFlow {
            val resting = state.gridFocused && !state.searchOpen
            DwellInput(
                state.focused?.channel?.guideId?.takeIf { resting },
                state.dwellNonce,
                state.previewFailed,
                currentSound,
            )
        }.collectLatest { input ->
            rest(input, state, dwell) { channel ->
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentOpen(channel, true)
            }
        }
    }
}

/** Times one rest on [input] until the preview opens full screen through [open], or nothing is left to time. */
private suspend fun rest(input: DwellInput, state: GuideState, dwell: PreviewDwell, open: (Channel) -> Unit) {
    if (dwell.channelId != input.channelId) dwell.onFocus(input.channelId, uptimeMs())
    while (true) {
        val now = uptimeMs()
        state.previewMuted = !input.sound || dwell.isMuted(now)
        if (dwell.opensFullScreen(now)) {
            // The same handoff as OK: the preview session is promoted, nothing reloads. A preview without a
            // picture is not opened; it would only carry its failure to the full screen.
            val channel = state.focused?.channel?.takeIf { it.guideId == dwell.channelId }
            if (channel != null && !input.failed) open(channel)
            return
        }
        delay(dwell.nextTransitionMs(now) ?: return)
    }
}

/** A key restarts the rest, and lets the full screen open by itself again. */
internal fun GuideState.restartRest(dwell: PreviewDwell) {
    dwell.onKey(uptimeMs())
    dwellNonce++
}

/**
 * A finger or mouse pressing or scrolling anywhere in the guide restarts the rest as a key does, and keeps the
 * focused channel from opening full screen under it until a key is pressed. Observed, never consumed.
 */
internal fun Modifier.restartsRestOnPointer(dwell: PreviewDwell, state: GuideState): Modifier = pointerInput(dwell) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                dwell.onPointer(uptimeMs())
                state.dwellNonce++
            }
        }
    }
}
