package dev.jamesrich.sofaking.ui.theme

import androidx.compose.ui.graphics.Color

/** Late-night living room: plum-navy walls, amber glow from the TV, lavender cushions. */
val SofaPlum = Color(0xFF14111F)
val SofaPlumRaised = Color(0xFF2A2440)
val SofaPlumLow = Color(0xFF1F1A30)
val SofaPlumHighest = Color(0xFF3A3354)
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
 * The light scheme, the same brand on daylight paper (the admin portal's light tokens). WCAG 2.1 contrast of text
 * pairs: ink on paper 15.7:1, on raised 14.1:1; muted on paper 6.6:1, on raised 5.9:1; amber ink on paper 7.3:1, on
 * raised 6.5:1; paper-white on amber ink 8.0:1; each soft tone on its pale tone at least 8.2:1; lavender on paper
 * 5.2:1, white on lavender 5.6:1; error on paper 6.0:1, white on error 6.5:1. Outline against paper is 3.4:1 and
 * against raised 3.0:1, the floor for control borders. The bright amber [SofaAmber] fails on paper (1.6:1), so the
 * light scheme's primary is the dark amber ink and the bright amber appears only as inversePrimary on ink.
 */
val SofaPaper = Color(0xFFF7F4FC)
val SofaPaperRaised = Color(0xFFECE6F7)
val SofaPaperWhite = Color(0xFFFFFFFF)
val SofaPaperLow = Color(0xFFF3EEFB)
val SofaPaperHighest = Color(0xFFDDD5EE)
val SofaInk = Color(0xFF1D1830)
val SofaInkMuted = Color(0xFF5B5275)
val SofaInkOutline = Color(0xFF8A7FA8)

val SofaAmberInk = Color(0xFF7A4300)
val SofaAmberPale = Color(0xFFFFE9C7)
val SofaAmberDark = Color(0xFF5A3A00)

val SofaLavenderInk = Color(0xFF6B4FD8)
val SofaLavenderPale = Color(0xFFE8E1FF)
val SofaLavenderDark = Color(0xFF2B1E6B)

val SofaErrorInk = Color(0xFFB3261E)
val SofaErrorPale = Color(0xFFFFDAD6)
val SofaErrorDark = Color(0xFF7A1A12)

val SofaOnAccent = Color(0xFFFFFFFF)

/**
 * Over live video. Plum at 88% keeps dusk text above 11:1 and mauve above 7:1 even over a white picture;
 * the mini-guide's denser 92% reads 13:1 and 8:1.
 */
val SofaOverlayScrim = SofaPlum.copy(alpha = 0.88f)
val SofaOverlayScrimDense = SofaPlum.copy(alpha = 0.92f)
val SofaOverlayTrack = SofaDusk.copy(alpha = 0.3f)
