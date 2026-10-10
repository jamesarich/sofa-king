package dev.jamesrich.sofaking.backend.catalog

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.*

class MutableClock(var now: Instant = Instant.parse("2026-10-10T13:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant() = now
}
class Fixture : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base = "http://127.0.0.1:${server.address.port}"
    var malformed = false
    var withdrawn = false
    val feed = FeedConfig("fixture", "$base/feed.m3u", "$base/guide.xml", probe = false)
    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val text = when (path) {
                "/feed.m3u" -> if (malformed) "not a playlist" else if (withdrawn) "#EXTM3U\n" else "#EXTM3U\n#EXTINF:-1 tvg-id=\"station\" tvg-chno=\"5\" group-title=\"News\",Station\n$base/master.m3u8\n"
                "/guide.xml" -> "<tv><programme channel=\"station\" start=\"20261010080000 -0500\" stop=\"20261010090000 -0500\"><title>Morning</title></programme></tv>"
                "/master.m3u8" -> "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=100000,AUDIO=\"audio\"\nmedia.m3u8\n"
                "/media.m3u8", "/audio.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n"
                "/key" -> "0123456789abcdef"
                else -> "fixture-segment"
            }
            val bytes = text.toByteArray()
            exchange.responseHeaders.set("Content-Type", if (path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream")
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == "bytes=0-3") {
                exchange.responseHeaders.set("Content-Range", "bytes 0-3/${bytes.size}")
                exchange.sendResponseHeaders(206, 4); exchange.responseBody.use { it.write(bytes, 0, 4) }
            } else { exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
        }
        server.start()
    }
    override fun close() { server.stop(0) }
}
