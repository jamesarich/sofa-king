package dev.jamesrich.sofaking.backend.catalog

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MutableClock(var now: Instant = Instant.parse("2026-10-10T13:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant() = now
}
class Fixture : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    val base = "http://127.0.0.1:${server.address.port}"
    var malformed = false
    var withdrawn = false
    /** One good entry plus one without an identity and one query-only duplicate. */
    var partial = false
    val feed = FeedConfig("fixture", "$base/feed.m3u", "$base/guide.xml", probe = false)
    /** A second provider carrying the same station, for lineup failover. */
    val altFeed = FeedConfig("alt", "$base/alt-feed.m3u", probe = false)
    /** The same guide as the provider's, gzip-compressed and with a channel block. */
    val gzipGuide = GuideConfig("gz", "$base/guide.xml.gz")
    /** A guide that always fails, for failure isolation. */
    val brokenGuide = GuideConfig("broken", "$base/broken-guide.xml")
    /**
     * The mjh shape: two 302 hops (the first `text/html`, the second with an empty body) ending at a
     * gzip XMLTV body with a `DOCTYPE`, served as `application/octet-stream`.
     */
    val redirectedDoctypeGuide = GuideConfig("mjh", "$base/hop1/us.xml.gz")
    /** The TVApp2 shape: plain XMLTV with a `DOCTYPE` on its own line, served as `application/xml`. */
    val doctypeGuide = GuideConfig("tvapp2", "$base/doctype-guide.xml")
    /** Requests for the external DTD or an external entity a hostile guide could name; must stay at zero. */
    val dtdRequests = AtomicInteger()
    val tsFeed = FeedConfig("ts", "$base/ts-feed.m3u", probe = false)
    /** An HLS master served from an extensionless path with a generic content type. */
    val extensionlessFeed = FeedConfig("ext", "$base/ext-feed.m3u", probe = false)
    /** A live MPEG-TS body behind a `.m3u8` path. */
    val mislabeledFeed = FeedConfig("fake", "$base/fake-feed.m3u", probe = false)
    val tsConnections = AtomicInteger()
    val tsClosed = AtomicInteger()
    /** Raw query string of the last media playlist request, for delivery-directive checks. */
    @Volatile var lastMediaQuery: String? = null
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (path == "/media.m3u8") lastMediaQuery = exchange.requestURI.rawQuery
            if (path == "/loop") {
                exchange.responseHeaders.set("Location", "$base/loop")
                exchange.sendResponseHeaders(302, -1); exchange.close()
                return@createContext
            }
            if (path == "/live.ts" || path == "/fake.m3u8") {
                tsConnections.incrementAndGet()
                exchange.responseHeaders.set("Content-Type", "video/mp2t")
                exchange.sendResponseHeaders(200, 0)
                try {
                    exchange.responseBody.use { out ->
                        var counter = 0
                        while (true) { out.write(tsPacket(counter++)); out.flush(); Thread.sleep(20) }
                    }
                } catch (_: IOException) {
                } catch (_: InterruptedException) {
                } finally { tsClosed.incrementAndGet() }
                return@createContext
            }
            if (path == "/guide.xml.gz") {
                val bytes = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(guideXml(true).toByteArray()) } }.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/gzip")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            if (path == "/broken-guide.xml") {
                exchange.sendResponseHeaders(503, -1); exchange.close()
                return@createContext
            }
            if (path == "/hop1/us.xml.gz") {
                exchange.responseHeaders.set("Content-Type", "text/html")
                exchange.responseHeaders.set("Location", "$base/hop2/raw/us.xml.gz")
                exchange.sendResponseHeaders(302, -1); exchange.close()
                return@createContext
            }
            if (path == "/hop2/raw/us.xml.gz") {
                exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                exchange.responseHeaders.set("Location", "$base/raw/us.xml.gz")
                exchange.sendResponseHeaders(302, 0); exchange.responseBody.close()
                return@createContext
            }
            if (path == "/raw/us.xml.gz") {
                val bytes = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(resource("guides/mjh-doctype.xml")) } }.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            if (path == "/doctype-guide.xml") {
                val bytes = resource("guides/tvapp2-doctype.xml")
                exchange.responseHeaders.set("Content-Type", "application/xml")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            if (path == "/xmltv.dtd" || path == "/entity.txt") {
                dtdRequests.incrementAndGet()
                val bytes = "FIXTURE-ENTITY-MARKER".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            val text = when (path) {
                "/feed.m3u" ->
                    if (malformed) "not a playlist"
                    else if (withdrawn) "#EXTM3U\n"
                    else if (partial) "#EXTM3U\n#EXTINF:-1 tvg-id=\"station\" tvg-chno=\"5\" group-title=\"News\",Station\n$base/master.m3u8\n#EXTINF:-1 tvg-chno=\"9\",Nameless Id\n$base/other.m3u8\n#EXTINF:-1 tvg-id=\"station\" tvg-chno=\"5\" group-title=\"News\",Station\n$base/master.m3u8?token=dup\n"
                    else "#EXTM3U\n#EXTINF:-1 tvg-id=\"station\" tvg-chno=\"5\" group-title=\"News\",Station\n$base/master.m3u8\n"
                "/alt-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"station\" tvg-chno=\"5\" group-title=\"News\",Station\n$base/alt.m3u8\n"
                "/ts-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"ts-station\" tvg-chno=\"6\",TS Station\n$base/live.ts\n"
                "/ext-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"ext-station\" tvg-chno=\"7\",Extensionless\n$base/resolve?id=1\n"
                "/fake-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"fake-station\" tvg-chno=\"8\",Mislabeled\n$base/fake.m3u8\n"
                "/resolve" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nmedia.m3u8\n"
                "/guide.xml" -> guideXml(false)
                "/master.m3u8" -> "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=100000,AUDIO=\"audio\"\nmedia.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200000\nloop\n"
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
    override fun close() { server.stop(0); executor.shutdownNow() }
    private fun guideXml(withChannels: Boolean): String = buildString {
        append("<tv>")
        if (withChannels) append("<channel id=\"station\"><display-name>Station HD</display-name><icon src=\"$base/station.png\"/></channel><channel id=\"other\"><display-name>Other</display-name></channel>")
        append("<programme channel=\"station\" start=\"20261010080000 -0500\" stop=\"20261010090000 -0500\"><title>Morning</title></programme>")
        append("<programme channel=\"other\" start=\"20261010080000 -0500\" stop=\"20261010090000 -0500\"><title>Elsewhere</title></programme>")
        append("</tv>")
    }
    companion object {
        fun resource(name: String): ByteArray = Fixture::class.java.classLoader.getResourceAsStream(name)!!.use { it.readBytes() }

        /** A PID 0x100 packet whose payload is the packet number. */
        fun tsPacket(number: Int): ByteArray = ByteArray(188) { index ->
            when (index) { 0 -> 0x47; 1 -> 0x01; 2 -> 0x00; 3 -> (0x10 or (number and 0x0F)).toByte(); else -> number.toByte() }
        }
    }
}
