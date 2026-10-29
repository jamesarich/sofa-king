package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.player_control_back
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LastInput
import org.jetbrains.compose.resources.stringResource

/** How long a player's bar stays up after the last key, tap or mouse movement, unless something holds it. */
internal const val BAR_IDLE_MS = 5_000L

/**
 * A button on the library and recordings player's bar. The same buttons serve the D-pad, which focuses them, and a
 * finger or a mouse, which presses them; [vodButtons] chooses which apply to what plays.
 */
internal enum class VodButton {
    /** Jumps over the commercial break playback is in. */
    SKIP_BREAK,

    /** Jumps over the intro playback is in. */
    SKIP_INTRO,

    /** Jumps over the credits playback is in, or plays the next episode when they run to the end. */
    SKIP_CREDITS,

    /** A library item's Audio and subtitles card. */
    TRACKS,

    /** A recording's captions, on or off. */
    CAPTIONS,

    /** The picture's [VideoZoom], one step on. */
    ZOOM,

    /** The [SleepTimer], one step on. */
    SLEEP,

    /** The playback speed, one step along [PLAYBACK_SPEEDS]. */
    SPEED,
    START_OVER,

    /** The next episode, without waiting for this one to end. */
    NEXT,

    /** The channel a recording still being written comes from, live. */
    BACK_TO_LIVE,
}

/**
 * The library and recordings player's buttons, in the bar's order. A break, an intro or the credits being played
 * leads, so Down and OK skip it; a library item with tracks to pick offers them and a recording its captions; then
 * Zoom and the sleep timer; Start over once [pastStart]; the next episode when there is one, unless the credits
 * button already leads to it ([creditsToNext]); and Back to live for a recording still being written ([toLive]). A
 * failed stream offers none: OK retries it.
 */
internal fun vodButtons(
    failed: Boolean,
    inBreak: Boolean,
    tracksPickable: Boolean,
    captions: Boolean,
    pastStart: Boolean,
    hasNext: Boolean,
    inIntro: Boolean = false,
    inCredits: Boolean = false,
    creditsToNext: Boolean = false,
    speedAdjustable: Boolean = false,
    toLive: Boolean = false,
): List<VodButton> = if (failed) {
    emptyList()
} else {
    buildList {
        if (inBreak) add(VodButton.SKIP_BREAK)
        if (inIntro) add(VodButton.SKIP_INTRO)
        if (inCredits) add(VodButton.SKIP_CREDITS)
        if (tracksPickable) add(VodButton.TRACKS)
        if (captions) add(VodButton.CAPTIONS)
        add(VodButton.ZOOM)
        add(VodButton.SLEEP)
        if (speedAdjustable) add(VodButton.SPEED)
        if (pastStart) add(VodButton.START_OVER)
        if (hasNext && !(inCredits && creditsToNext)) add(VodButton.NEXT)
        if (toLive) add(VodButton.BACK_TO_LIVE)
    }
}

/** A button on the live player's bar; [liveButtons] chooses them. */
internal enum class LiveButton {
    CHANNELS,

    /** The channel watched before this one, as long-press OK and the Last key recall it. */
    PREVIOUS,

    /** Pause or play, or Retry while the stream has failed. */
    PLAY_PAUSE,
    CAPTIONS,

    /** The stream card, which lists the audio tracks. */
    STREAM_INFO,

    /** The picture's [VideoZoom], one step on. */
    ZOOM,

    /** The [SleepTimer], one step on. */
    SLEEP,

    /** Plays the recording of the program on now from its start, while it is still being written. */
    FROM_START,

    /** Records the program on now, or cancels its recording. */
    RECORD,
    FAVORITE,

    /** Several channels at once, which the server draws into one picture. */
    MULTIVIEW,
    GUIDE,
}

/**
 * The live player's buttons, in the bar's order: the channel list first, the previous channel once there is one
 * ([hasPrevious]), then what the CC, Play/Pause, Info and Zoom keys do, the sleep timer, Watch from start while the
 * program on now is recording ([canWatchFromStart]), Record where the server records and there is a program on, the
 * star where favorites can change, Multiview where the server draws it ([canMultiview]), and the guide. A finger or a
 * mouse has play and pause beside the channel buttons instead ([pointer]).
 */
internal fun liveButtons(
    pointer: Boolean,
    canRecord: Boolean,
    canFavorite: Boolean,
    hasPrevious: Boolean = false,
    canWatchFromStart: Boolean = false,
    canMultiview: Boolean = false,
): List<LiveButton> = buildList {
    add(LiveButton.CHANNELS)
    if (hasPrevious) add(LiveButton.PREVIOUS)
    if (!pointer) add(LiveButton.PLAY_PAUSE)
    add(LiveButton.CAPTIONS)
    add(LiveButton.STREAM_INFO)
    add(LiveButton.ZOOM)
    add(LiveButton.SLEEP)
    if (canWatchFromStart) add(LiveButton.FROM_START)
    if (canRecord) add(LiveButton.RECORD)
    if (canFavorite) add(LiveButton.FAVORITE)
    if (canMultiview) add(LiveButton.MULTIVIEW)
    add(LiveButton.GUIDE)
}

/** How far into an item Start over is offered. */
internal const val START_OVER_AFTER_MS = 30_000L

/** Where a key goes while a button on the bar has focus. */
internal enum class ButtonRowKey {
    /** The focused button or the row's own focus moves take it. */
    ROW,

    /** Focus goes back to the picture; the bar stays up. */
    LEAVE,

    /** Focus goes back to the picture, and the key acts there as it would have. */
    PLAYER,
}

/**
 * Left and Right move along the row and OK presses the focused button; Up, Back and Escape leave the row for the
 * picture; Down stays put; every other key (media keys, digits) goes back to the picture and acts there.
 */
internal fun Key.inButtonRow(): ButtonRowKey = when (this) {
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
    Key.DirectionDown,
    -> ButtonRowKey.ROW

    Key.DirectionUp, Key.Back, Key.Escape -> ButtonRowKey.LEAVE

    else -> ButtonRowKey.PLAYER
}

/**
 * Where a double tap on a touch screen's picture seeks: its left third back (-1), its right third forward
 * (1), the middle not at all (0).
 */
internal fun doubleTapSeek(x: Float, width: Float): Int = when {
    width <= 0f -> 0
    x < width / SEEK_ZONES -> -1
    x > width - width / SEEK_ZONES -> 1
    else -> 0
}

/**
 * The bar's row of buttons, the same for the D-pad and for a finger or mouse. [entry] focuses the first button,
 * which is where Down from the picture lands; [onFocus] says whether a button holds focus, so the player can send
 * keys there; Left and Right back into the row return to the button that had focus. With [showLabel] the focused
 * button's name shows under the row, as a remote has no hover.
 */
@Composable
internal fun PlayerButtonRow(
    items: List<PlayerButtonItem>,
    entry: FocusRequester,
    onFocus: (Boolean) -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    // The focused button by place, so its label follows a press that changes it (Pause to Play, Zoom: Fit to Fill).
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BAR_LABEL_GAP)) {
        Row(
            Modifier.focusRestorer(entry).focusGroup().onFocusChanged {
                onFocus(it.hasFocus)
                if (!it.hasFocus) focusedIndex = null
            },
            horizontalArrangement = Arrangement.spacedBy(BAR_ROW_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { position, item ->
                ControlIcon(
                    item.icon,
                    item.label,
                    item.onClick,
                    modifier = Modifier
                        .then(if (position == 0) Modifier.focusRequester(entry) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedIndex = position }
                        .then(
                            if (item.state != null) {
                                Modifier.semantics { stateDescription = item.state }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
        val label = focusedIndex?.let { items.getOrNull(it)?.label }
        if (showLabel && label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Leaves the player, for a finger or a mouse; a remote has its Back key. */
@Composable
internal fun PlayerBackControl(onBack: () -> Unit) {
    ControlIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.player_control_back), onBack)
}

/**
 * A mouse moving over the picture brings the bar up with its pointer controls, as desktop and web players do; the
 * app counts a mouse move as pointer input only here, so hovering elsewhere never changes a screen.
 */
internal fun Modifier.showBarOnMouseMove(last: LastInput, onMove: () -> Unit): Modifier = pointerInput(last) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Move && event.changes.any { it.type == PointerType.Mouse }) {
                last.kind = InputKind.POINTER
                onMove()
            }
        }
    }
}

/** The picture splits into this many zones across for a double tap: back, nothing, forward. */
private const val SEEK_ZONES = 3
