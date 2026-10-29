package dev.jamesrich.sofaking.data

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** How many segments a live playlist lists before AVPlayer starts it. */
const val START_SEGMENTS = 3

private const val START_TRIES = 40

/**
 * Whether AVPlayer can start the HLS playlist [text]: it is not a media playlist, it is finished, or it lists enough
 * segments. A null [text] is a failed read, which the player itself reports.
 */
fun startsAlready(text: String?): Boolean = text == null || !text.contains("#EXTINF") ||
    text.contains("#EXT-X-ENDLIST") || text.split("#EXTINF").size - 1 >= START_SEGMENTS

/**
 * Returns once the live playlist [fetch] reads lists [START_SEGMENTS] segments, or at once when it is not a live media
 * playlist. AVPlayer starts a live stream no nearer its end than three segments: handed one with fewer it fetches
 * nothing and waits for good, and it gives up on a playlist request the server holds past about 20 seconds. So the
 * app asks for the playlist itself first, and the item's own first request then answers at once.
 */
suspend fun awaitLiveSegments(fetch: suspend () -> String?) {
    var tries = 0
    while (tries++ < START_TRIES && !startsAlready(fetch())) delay(1.seconds)
}
