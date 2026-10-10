package dev.jamesrich.sofaking.data

import android.util.Xml
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

class DispatcharrRepository(
    private val baseUrl: String,
    private val profile: String,
    private val http: OkHttpClient = sharedHttp,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The playlist alone: small and fast, so the UI can show channels before the guide arrives. */
    suspend fun loadChannels(): List<Channel> = withContext(ioDispatcher) {
        fetch("$baseUrl/output/m3u/${ServerAddress.profilePath(profile)}") { M3uParser.parse(it.bufferedReader().readText()) }
    }

    /** Programmes for [channels]; one day is enough for the guide and parses ~2.5x faster than the default window. */
    suspend fun loadGuide(channels: List<Channel>): Map<String, List<Programme>> = withContext(ioDispatcher) {
        val wanted = channels.mapTo(HashSet()) { it.guideId }
        fetch("$baseUrl/output/epg/${ServerAddress.profilePath(profile)}?days=$GUIDE_DAYS") { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            XmltvParser.parse(parser, wanted)
        }
    }

    private suspend fun <T> fetch(url: String, read: (InputStream) -> T): T = coroutineScope {
        val call = http.newCall(Request.Builder().url(url).build())
        // Cancellation closes an active socket even while the IO thread is reading the body.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(ioDispatcher) {
                call.execute().use { response ->
                    check(response.isSuccessful) { "Server returned HTTP ${response.code}" }
                    response.body.byteStream().use(read)
                }
            }
        } finally {
            cancellation.cancelAndJoin()
        }
    }

    private companion object {
        const val GUIDE_DAYS = 1
        val sharedHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
