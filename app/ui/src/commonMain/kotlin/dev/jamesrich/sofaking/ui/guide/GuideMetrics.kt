package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.ui.LayoutProfile

internal val PIN_WIDTH = 200.dp

/** The pinned column in a compact-width window: the logo and the number, without the name. */
internal val COMPACT_PIN_WIDTH = 104.dp
internal val MINUTE_WIDTH = 6.dp

// 1080p TV budget (540dp tall): app header, band 144 + 8 and the time header 32 leave four whole 48dp rows above the
// overscan, and the top of a fifth; a row fits Material's body medium title and body small times.
internal val ROW_HEIGHT = 48.dp

/** The compact density's row: one more channel on a 1080p TV, with the row's type a step smaller. */
internal val COMPACT_ROW_HEIGHT = 40.dp

/** The search result line under a channel that has upcoming programs matching the query. */
internal val LATER_ROW_HEIGHT = 40.dp

/** The logo slot at the start of the pinned column. */
internal val LOGO_WIDTH = 48.dp
internal val LOGO_HEIGHT = 32.dp
internal val HEADER_HEIGHT = 32.dp
internal val MIN_STICKY_TEXT = 48.dp
internal const val FOCUS_MARGIN_MINUTES = 30L

/** Of the rows on screen, the focused one sits in this slot (0-based) while the list scrolls under it. */
internal const val FOCUS_ROW_SLOT = 1

/** The guide's row density; the app provides the setting, and the TV keeps the comfortable default. */
internal val LocalGuideDensity = staticCompositionLocalOf { GuideDensity.COMFORTABLE }

/** A channel row's height at [density]. */
internal fun rowHeight(density: GuideDensity): Dp = when (density) {
    GuideDensity.COMFORTABLE -> ROW_HEIGHT
    GuideDensity.COMPACT -> COMPACT_ROW_HEIGHT
}

/** The pinned column's width; a compact-width window gives the timeline the rest of a phone held upright. */
internal fun pinWidth(profile: LayoutProfile): Dp = if (profile.compactWidth) COMPACT_PIN_WIDTH else PIN_WIDTH

internal fun nowOffset(minute: Long): Dp = MINUTE_WIDTH * minute.toFloat()
