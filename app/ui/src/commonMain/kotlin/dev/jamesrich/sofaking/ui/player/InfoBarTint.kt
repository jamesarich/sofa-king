package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaPlum

/** How far the info bar's lower edge moves from plum toward the logo's ambient tint. */
internal const val INFO_BAR_TINT_SHARE = 0.5f

/**
 * The info bar's lower edge: plum moved [INFO_BAR_TINT_SHARE] of the way to [tint], at the scrim's opacity. The tint
 * is capped dark by `ambientTint`, so text keeps its contrast over any picture.
 */
internal fun infoBarEdge(tint: Color): Color =
    lerp(SofaPlum, tint, INFO_BAR_TINT_SHARE).copy(alpha = SofaOverlayScrim.alpha)

/** The info bar's background: the overlay scrim at the top, fading to [infoBarEdge] at the bottom. */
internal fun infoBarBrush(tint: Color): Brush = Brush.verticalGradient(listOf(SofaOverlayScrim, infoBarEdge(tint)))
