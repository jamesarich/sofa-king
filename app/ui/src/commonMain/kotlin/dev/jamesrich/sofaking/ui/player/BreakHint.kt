package dev.jamesrich.sofaking.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.break_show_picture
import dev.jamesrich.sofaking.resources.break_show_picture_tap
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import org.jetbrains.compose.resources.stringResource

/** How the break screen says what brings the picture back: OK on a remote, a tap for a finger or a mouse. */
@Composable
internal fun ShowPictureHint(ink: Color, modifier: Modifier = Modifier) {
    val pointerDriven = LocalLastInput.current.kind == InputKind.POINTER
    Text(
        stringResource(if (pointerDriven) Res.string.break_show_picture_tap else Res.string.break_show_picture),
        style = MaterialTheme.typography.bodySmall,
        color = ink.copy(alpha = FAINT_ALPHA),
        modifier = modifier,
    )
}
