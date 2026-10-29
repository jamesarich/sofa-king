package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.fetchText
import platform.Foundation.NSURL
import dev.jamesrich.sofaking.data.awaitLiveSegments as awaitSegments

/** Whether this is an HLS playlist, whose body is small enough to read; anything else is handed to AVPlayer as is. */
internal fun NSURL.isPlaylist(): Boolean = path?.endsWith(".m3u8") == true

/** Returns once the live playlist at [url] is ready for AVPlayer to start; see [awaitSegments]. */
internal suspend fun awaitLiveSegments(url: NSURL) = awaitSegments { fetchText(url) }
