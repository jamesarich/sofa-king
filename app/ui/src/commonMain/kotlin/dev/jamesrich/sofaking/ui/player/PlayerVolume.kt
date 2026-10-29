package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.player_control_mute
import dev.jamesrich.sofaking.resources.player_control_unmute
import dev.jamesrich.sofaking.resources.player_control_volume
import dev.jamesrich.sofaking.resources.player_volume_level
import dev.jamesrich.sofaking.resources.player_volume_muted
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.tvFocus
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** How far a key press or a wheel notch moves the volume, in percent. */
internal const val VOLUME_STEP = 5

/**
 * The players' own volume where the app draws a control for it ([adjustable]: a desktop window or a browser page,
 * see [LivePlayer.drawsVolumeControl]). On a TV, phone or Apple TV the system's volume rules and [gain] stays full.
 * The app restores [level] and [muted] from the settings and keeps them there; [nudges] counts the changes made by a
 * key or the wheel, which the players answer with a short pill, since nothing else on screen shows them.
 */
@Stable
class PlayerVolume(val adjustable: Boolean) {
    var level by mutableIntStateOf(AppSettings.MAX_VOLUME)
        private set
    var muted by mutableStateOf(false)
        private set
    var nudges by mutableIntStateOf(0)
        private set

    /** What the engine plays at, from 0 to 1. */
    val gain: Float get() = when {
        !adjustable -> 1f
        muted -> 0f
        else -> level.toFloat() / AppSettings.MAX_VOLUME
    }

    fun restore(level: Int, muted: Boolean) {
        this.level = level.coerceIn(0, AppSettings.MAX_VOLUME)
        this.muted = muted
    }

    /** The slider's level; moving it unmutes. */
    fun set(level: Int) {
        this.level = level.coerceIn(0, AppSettings.MAX_VOLUME)
        muted = false
    }

    fun toggleMute() {
        muted = !muted
    }

    /** One [VOLUME_STEP] up (positive) or down (negative), from a key or the wheel; a step unmutes. */
    fun step(direction: Int) {
        set(level + direction.coerceIn(-1, 1) * VOLUME_STEP)
        nudges++
    }

    /** Acts on a [VolumeKey]; false where the app draws no control, so the key goes on to the player. */
    fun press(key: VolumeKey): Boolean {
        if (!adjustable) return false
        when (key) {
            VolumeKey.UP -> step(1)

            VolumeKey.DOWN -> step(-1)

            VolumeKey.MUTE -> {
                toggleMute()
                nudges++
            }
        }
        return true
    }
}

/** The players' volume; its default draws no control and plays at full, as the system volume platforms do. */
val LocalPlayerVolume = staticCompositionLocalOf { PlayerVolume(adjustable = false) }

/** A keyboard's volume keys in a full screen player. */
enum class VolumeKey { UP, DOWN, MUTE }

/**
 * Plus (or Equals, its unshifted key) and Minus step the volume, M mutes. The arrows are taken: Up and Down zap a
 * channel, Left and Right seek a recording.
 */
internal fun Key.volumeKey(): VolumeKey? = when (this) {
    Key.Plus, Key.Equals, Key.NumPadAdd -> VolumeKey.UP
    Key.Minus, Key.NumPadSubtract -> VolumeKey.DOWN
    Key.M -> VolumeKey.MUTE
    else -> null
}

/**
 * The mute switch and the volume slider, where the app draws them; nothing elsewhere. [onAdjust] runs as the slider
 * moves, to keep the bar up; [onDone] when the switch is pressed or the slider let go, to hand focus back.
 */
@Composable
internal fun VolumeControl(onAdjust: () -> Unit, onDone: () -> Unit) {
    val volume = LocalPlayerVolume.current
    if (!volume.adjustable) return
    val silent = volume.muted || volume.level == 0
    val label = stringResource(Res.string.player_control_volume)
    val levelText = if (volume.muted) {
        stringResource(
            Res.string.player_volume_muted,
        )
    } else {
        stringResource(Res.string.player_volume_level, volume.level)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ControlIcon(
            if (silent) SofaIcons.VolumeOff else SofaIcons.VolumeUp,
            stringResource(if (volume.muted) Res.string.player_control_unmute else Res.string.player_control_mute),
            {
                volume.toggleMute()
                onDone()
            },
        )
        val shown = if (volume.muted) 0f else volume.level.toFloat()
        val slider = rememberSliderState(shown, trackRange = 0f..AppSettings.MAX_VOLUME.toFloat())
        // The slider follows a key, the wheel or the mute switch; a drag reaches it the same way, through the volume.
        LaunchedEffect(shown) { slider.value = shown }
        Slider(
            state = slider,
            onValueChange = {
                volume.set(it.roundToInt())
                onAdjust()
            },
            onValueChangeFinished = onDone,
            modifier = Modifier
                .width(VOLUME_SLIDER_WIDTH)
                .tvFocus(MaterialTheme.shapes.small, focusedScale = 1f)
                .semantics {
                    contentDescription = label
                    stateDescription = levelText
                },
        )
    }
}

/** Where the volume went after a key or the wheel moved it; shown for a moment in the player's pill column. */
@Composable
internal fun ColumnScope.VolumeToast() {
    val volume = LocalPlayerVolume.current
    // A change made in an earlier player is not this one's to announce.
    val opened = remember { volume.nudges }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(volume.nudges) {
        if (volume.nudges == opened) return@LaunchedEffect
        shown = true
        delay(STATE_TOAST_MS)
        shown = false
    }
    AnimatedVisibility(shown, enter = PopIn, exit = PopOut) {
        StatusPill(
            if (volume.muted) {
                stringResource(
                    Res.string.player_volume_muted,
                )
            } else {
                stringResource(Res.string.player_volume_level, volume.level)
            },
        )
    }
}

private val VOLUME_SLIDER_WIDTH = 120.dp
