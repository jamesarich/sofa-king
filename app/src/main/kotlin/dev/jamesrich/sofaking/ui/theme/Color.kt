package dev.jamesrich.sofaking.ui.theme

import androidx.compose.ui.graphics.Color

/** Late-night living room: plum-navy walls, amber glow from the TV, lavender cushions. */
val SofaPlum = Color(0xFF14111F)
val SofaPlumRaised = Color(0xFF2A2440)
val SofaDusk = Color(0xFFF4EFFA)
val SofaMauve = Color(0xFFC4BAD9)
val SofaOutline = Color(0xFF8C80A8)

val SofaAmber = Color(0xFFFFB547)
val SofaAmberDeep = Color(0xFF3D2B12)
val SofaAmberSoft = Color(0xFFFFD9A0)

val SofaLavender = Color(0xFFC9B8FF)
val SofaLavenderDeep = Color(0xFF332A52)
val SofaLavenderSoft = Color(0xFFE6DDFF)

val SofaError = Color(0xFFFFB4AB)
val SofaErrorDeep = Color(0xFF5C1A14)
val SofaErrorSoft = Color(0xFFFFDAD6)

val SofaScrim = Color(0xFF000000)

/**
 * Over live video. Plum at 88% keeps dusk text above 11:1 and mauve above 7:1 even over a white picture;
 * the mini-guide's denser 92% reads 13:1 and 8:1.
 */
val SofaOverlayScrim = SofaPlum.copy(alpha = 0.88f)
val SofaOverlayScrimDense = SofaPlum.copy(alpha = 0.92f)
val SofaOverlayTrack = SofaDusk.copy(alpha = 0.3f)
