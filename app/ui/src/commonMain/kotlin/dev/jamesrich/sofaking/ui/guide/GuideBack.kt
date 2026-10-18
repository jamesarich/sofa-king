package dev.jamesrich.sofaking.ui.guide

/**
 * What Back does in the guide. [ARM_EXIT] shows the press-again hint; [EXIT] leaves it to the activity, which
 * finishes.
 */
internal enum class GuideBack { CLOSE_SEARCH, FOCUS_CHIPS, FOCUS_HEADER, ARM_EXIT, EXIT }

/**
 * Open search closes first; from the grid Back goes up to the chips, from the chips to the header's tabs when
 * there are any, and from there the first press asks for a second, which exits while [exitArmed].
 */
internal fun guideBackAction(searchOpen: Boolean, gridFocused: Boolean, chipsFocused: Boolean, hasHeader: Boolean, exitArmed: Boolean): GuideBack = when {
    searchOpen -> GuideBack.CLOSE_SEARCH
    gridFocused -> GuideBack.FOCUS_CHIPS
    chipsFocused && hasHeader -> GuideBack.FOCUS_HEADER
    exitArmed -> GuideBack.EXIT
    else -> GuideBack.ARM_EXIT
}

/** How long the second Back that exits has, after the first shows the hint. */
internal const val EXIT_CONFIRM_MS = 2_500L

/** Position of [active] in the chip row, after the Now and Search chips. */
internal fun selectedChipIndex(filters: List<GuideFilter>, active: GuideFilter): Int =
    FIXED_CHIPS + filters.indexOf(active).coerceAtLeast(0)

private const val FIXED_CHIPS = 2
