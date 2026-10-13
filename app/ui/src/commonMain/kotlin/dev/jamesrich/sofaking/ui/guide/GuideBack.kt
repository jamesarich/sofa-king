package dev.jamesrich.sofaking.ui.guide

/** What Back does in the guide. [EXIT] leaves it to the activity, which finishes. */
internal enum class GuideBack { CLOSE_SEARCH, FOCUS_CHIPS, EXIT }

/** Open search closes first; from the grid Back goes up to the chips; from the chips or the header it exits. */
internal fun guideBackAction(searchOpen: Boolean, gridFocused: Boolean): GuideBack = when {
    searchOpen -> GuideBack.CLOSE_SEARCH
    gridFocused -> GuideBack.FOCUS_CHIPS
    else -> GuideBack.EXIT
}

/** Position of [active] in the chip row, after the Now and Search chips. */
internal fun selectedChipIndex(filters: List<GuideFilter>, active: GuideFilter): Int =
    FIXED_CHIPS + filters.indexOf(active).coerceAtLeast(0)

private const val FIXED_CHIPS = 2
