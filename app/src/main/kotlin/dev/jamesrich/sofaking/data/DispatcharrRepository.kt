package dev.jamesrich.sofaking.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class DispatcharrRepository(
    private val baseUrl: String,
    private val profile: String,
) {
    /** The playlist alone: small and fast, so the UI can show channels before the guide arrives. */
    suspend fun loadChannels(): List<Channel> = withContext(Dispatchers.IO) {
        fetch("$baseUrl/output/m3u/$profile") { M3uParser.parse(it.bufferedReader().readText()) }
    }

    /** Programmes for [channels]; one day is enough for the guide and parses ~2.5x faster than the default window. */
    suspend fun loadGuide(channels: List<Channel>): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        val wanted = channels.mapTo(HashSet()) { it.guideId }
        fetch("$baseUrl/output/epg/$profile?days=$GUIDE_DAYS") { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            XmltvParser.parse(parser, wanted)
        }
    }

    private fun <T> fetch(url: String, read: (InputStream) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code from $url" }
            return connection.inputStream.buffered(64 * 1024).use(read)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val GUIDE_DAYS = 1
    }
}
