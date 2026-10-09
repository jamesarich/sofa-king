package dev.jamesrich.sofaking.ui.guide

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val PIN_WIDTH = 240.dp
internal val MINUTE_WIDTH = 6.dp
// 1080p TV budget (540dp tall): app header ~90 + band 144 + 8 + header 32 leaves four 56dp rows above the overscan.
internal val ROW_HEIGHT = 56.dp
internal val HEADER_HEIGHT = 32.dp
internal val MIN_STICKY_TEXT = 48.dp
internal const val FOCUS_MARGIN_MINUTES = 30L

internal fun nowOffset(minute: Long): Dp = MINUTE_WIDTH * minute.toFloat()
