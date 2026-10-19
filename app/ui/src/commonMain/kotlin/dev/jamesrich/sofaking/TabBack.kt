package dev.jamesrich.sofaking

/** What Back does on the Library and Recordings tabs. */
internal enum class TabBack { FOCUS_HEADER, OPEN_LIVE }

/** From the page Back climbs to the header's open tab when there is a header, and from there it opens Live. */
internal fun tabBackAction(hasHeader: Boolean, headerFocused: Boolean): TabBack =
    if (hasHeader && !headerFocused) TabBack.FOCUS_HEADER else TabBack.OPEN_LIVE
