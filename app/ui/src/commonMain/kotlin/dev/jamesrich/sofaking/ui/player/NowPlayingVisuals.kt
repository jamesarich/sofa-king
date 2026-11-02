package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.music_fullscreen
import dev.jamesrich.sofaking.resources.music_next_look
import dev.jamesrich.sofaking.resources.music_off
import dev.jamesrich.sofaking.resources.music_on
import dev.jamesrich.sofaking.resources.music_visualizer
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.visualizer.MusicVisualizer
import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import org.jetbrains.compose.resources.stringResource

/**
 * The visualizer behind the page, on the look shown last ([preset]); [bare] when the controls are hidden, which also
 * names the song over it.
 */
@Composable
internal fun VisualsLayer(
    tap: AudioAnalyzer,
    song: MediaItem,
    skips: Int,
    bare: Boolean,
    preset: String?,
    onPreset: (String) -> Unit,
) {
    MusicVisualizer(
        tap,
        song.id,
        skips,
        preset,
        onPreset,
        song.title,
        song.trackArtist ?: song.artist,
        showSong = bare,
        Modifier.fillMaxSize(),
    )
}

/** A page-sized target over a full-screen visualizer: a key, a click or a tap brings the controls back. */
@Composable
internal fun ExitFullscreenOnInput(exit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { requestFocusWhenAttached(focus) }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) exit()
                true
            }
            .focusable()
            .clickable(interactionSource = null, indication = null, onClick = exit),
    )
}

/**
 * The page's controls, or only a hit target that calls [exit] when [bare]; a [scrimmed] page has a dark scrim under
 * its controls so they stay legible over a bright picture.
 */
@Composable
internal fun ControlsOrFullscreen(
    bare: Boolean,
    scrimmed: Boolean,
    exit: () -> Unit,
    controls: @Composable () -> Unit,
) {
    if (bare) {
        ExitFullscreenOnInput(exit)
    } else {
        if (scrimmed) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = VISUALS_SCRIM)),
            )
        }
        controls()
    }
}

/** The Visualizer toggle, then Next look and Full screen, which keep their slots while it is off. */
@Composable
internal fun VisualToggles(visuals: VisualsChoice) {
    Toggle(
        SofaIcons.GraphicEq,
        stringResource(Res.string.music_visualizer),
        stringResource(if (visuals.shown) Res.string.music_on else Res.string.music_off),
        visuals.shown,
        visuals.toggle,
    )
    Row {
        if (visuals.shown) {
            ControlIcon(SofaIcons.AutoAwesome, stringResource(Res.string.music_next_look), visuals.next)
            ControlIcon(SofaIcons.Fullscreen, stringResource(Res.string.music_fullscreen), visuals.fullscreen)
        } else {
            Spacer(Modifier.size(CONTROL_SIZE * 2))
        }
    }
}

/**
 * Whether the visualizer shows behind the page, the button that [toggle]s it, the one that asks for the [next] look and
 * the one that takes it [fullscreen]; where the engine can be tapped.
 */
internal class VisualsChoice(
    val shown: Boolean,
    val toggle: () -> Unit,
    val next: () -> Unit,
    val fullscreen: () -> Unit,
)

/** How dark the scrim is over the visualizer. */
private const val VISUALS_SCRIM = 0.35f
