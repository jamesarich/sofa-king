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
    suspend fun load(): Lineup = withContext(Dispatchers.IO) {
        val channels = fetch("$baseUrl/output/m3u/$profile") { M3uParser.parse(it.bufferedReader().readText()) }
        val wanted = channels.mapTo(HashSet()) { it.guideId }
        val guide = fetch("$baseUrl/output/epg/$profile") { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            XmltvParser.parse(parser, wanted)
        }
        Lineup(channels, guide)
    }

    private fun <T> fetch(url: String, read: (InputStream) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code from $url" }
            return connection.inputStream.buffered().use(read)
        } finally {
            connection.disconnect()
        }
    }
}
