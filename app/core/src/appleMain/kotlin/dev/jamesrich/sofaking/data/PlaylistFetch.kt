package dev.jamesrich.sofaking.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithURL
import kotlin.coroutines.resume

/** The body of the small text resource at [url], such as an HLS playlist, or null when it could not be read. */
@OptIn(BetaInteropApi::class) // NSString.create(data:encoding:)
suspend fun fetchText(url: NSURL): String? = suspendCancellableCoroutine<String?> { continuation ->
    val task = NSURLSession.sharedSession.dataTaskWithURL(url) { data: NSData?, _, _ ->
        continuation.resume(data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() })
    }
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
}
