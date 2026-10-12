package dev.jamesrich.sofaking.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** The app's one connection pool; clients with other timeouts are derived from it with `newBuilder()`. */
internal val sharedHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
}

/** Runs [request] on [ioDispatcher] and hands the open response to [read]; cancellation closes the socket even mid-body. */
internal suspend fun <T> OkHttpClient.fetch(request: Request, ioDispatcher: CoroutineDispatcher, read: (Response) -> T): T = coroutineScope {
    val call = newCall(request)
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        withContext(ioDispatcher) { call.execute().use(read) }
    } finally {
        cancellation.cancelAndJoin()
    }
}
