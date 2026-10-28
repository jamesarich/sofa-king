package dev.jamesrich.sofaking.ui.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

private const val START_TRIES = 40

/** Whether this is an HLS playlist, whose body is small enough to read; anything else is handed to AVPlayer as is. */
internal fun NSURL.isPlaylist(): Boolean = path?.endsWith(".m3u8") == true

/**
 * Returns once the live playlist at [url] lists three segments, or at once when it is not a live media playlist.
 * AVPlayer starts a live stream no nearer its end than three segments: handed one with fewer it fetches nothing and
 * waits for good, and it gives up on a playlist request the server holds past about 20 seconds. So the app asks for
 * the playlist itself first, and the item's own first request then answers at once.
 */
internal suspend fun awaitLiveSegments(url: NSURL) {
    var tries = 0
    while (tries++ < START_TRIES && !startsAlready(fetchText(url))) delay(1.seconds)
}

private suspend fun fetchText(url: NSURL): String? = suspendCancellableCoroutine<String?> { continuation ->
    val task = NSURLSession.sharedSession.dataTaskWithURL(url) { data: NSData?, _, _ ->
        continuation.resume(data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() })
    }
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
}
