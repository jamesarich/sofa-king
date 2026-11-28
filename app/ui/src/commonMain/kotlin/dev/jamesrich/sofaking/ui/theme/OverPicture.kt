package dev.jamesrich.sofaking.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * The panel behind anything drawn over the picture (a toast, a pill, a card, the control bar): the overlay scrim in
 * [shape], the theme's small shape unless the panel is a round badge. Its content goes inside [OverVideo], so the text
 * on it is the dark scheme's in a light app too.
 */
@Composable
internal fun Modifier.overlaySurface(shape: Shape = MaterialTheme.shapes.small): Modifier =
    background(SofaOverlayScrim, shape)
