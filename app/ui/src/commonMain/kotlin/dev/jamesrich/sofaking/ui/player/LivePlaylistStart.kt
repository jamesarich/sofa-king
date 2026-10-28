package dev.jamesrich.sofaking.ui.player

/** How many segments a live playlist lists before AVPlayer starts it. */
internal const val START_SEGMENTS = 3

/**
 * Whether AVPlayer can start the HLS playlist [text]: it is not a media playlist, it is finished, or it lists enough
 * segments. A null [text] is a failed read, which the player itself reports.
 */
internal fun startsAlready(text: String?): Boolean = text == null || !text.contains("#EXTINF") ||
    text.contains("#EXT-X-ENDLIST") || text.split("#EXTINF").size - 1 >= START_SEGMENTS
