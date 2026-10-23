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
    /** A third provider carrying a channel named like the station but under another `tvg-id`, for equivalence evidence. */
    val twinFeed = FeedConfig("twin", "$base/twin-feed.m3u", probe = false)
    /** An HLS master served from an extensionless path with a generic content type. */
    val extensionlessFeed = FeedConfig("ext", "$base/ext-feed.m3u", probe = false)
    /** A live MPEG-TS body behind a `.m3u8` path. */
    val mislabeledFeed = FeedConfig("fake", "$base/fake-feed.m3u", probe = false)
    /**
     * The masqueradarr shape: a percent-encoded upstream URL in an extensionless proxy path with a `token` query,
     * answered with a chunked media playlist whose first chunk is shorter than `#EXTM3U`.
     */
    val proxyFeed = FeedConfig("proxy", "$base/proxy-feed.m3u", probe = false)
    val proxyStreamUrl = "$base/dlhd/https%3A%2F%2Fexample.test%2Fstream%3Fid%3D521?token=abc"
    val proxyRequests = AtomicInteger()
    /** The station with a `tvg-logo` naming this fixture's PNG, for the logo cache. */
    val logoFeed = FeedConfig("logo", "$base/branded-feed.m3u", probe = false)
    val logoUrl = "$base/logo.png"
    /**
     * Two services with their own guides: `Learn Show` and `Learn Show TV` air one schedule, `NFL Network` and
     * `NFL Channel` two different ones, from 2026-10-10T13:00Z.
     */
    val learnFeed = FeedConfig("learn", "$base/learn-feed.m3u", "$base/learn-guide.xml", probe = false)
    val learnTwinFeed = FeedConfig("learn2", "$base/learn2-feed.m3u", "$base/learn2-guide.xml", probe = false)
    /** Requests for any `/logo*` or `/decoy*` path. */
    val logoRequests = AtomicInteger()
    /** While set, `/logo.png` answers 503. */
    @Volatile var logoBroken = false
    /** While set, `/key` and the media playlists are served gzip-encoded with a `Content-Encoding` header, as some CDNs do unasked. */
    @Volatile var gzipEncoded = false
    val tsConnections = AtomicInteger()
    val tsClosed = AtomicInteger()
    /** How long `/live.ts` waits after a connection before it answers. */
    @Volatile var tsStartDelayMs = 0L
    /** Raw query string of the last media playlist request, for delivery-directive checks. */
    @Volatile var lastMediaQuery: String? = null
    /** While set, the guide's `Morning` programme carries a sub-title, season and episode numbering, a rating and `<new/>`. */
    @Volatile var guideMetadata = false
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
            if (exchange.requestURI.rawPath.startsWith("/dlhd/")) {
                proxyRequests.incrementAndGet()
                exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { out ->
                    out.write("#EX".toByteArray()); out.flush(); Thread.sleep(100)
                    out.write("TM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:4,\nsegment.ts\n".toByteArray())
                }
                return@createContext
            }
            if (path == "/live.ts" || path == "/fake.m3u8") {
                tsConnections.incrementAndGet()
                if (tsStartDelayMs > 0) Thread.sleep(tsStartDelayMs)
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
            if (path.startsWith("/logo") || path.startsWith("/decoy")) {
                logoRequests.incrementAndGet()
                val (type, bytes) = when (path) {
                    // `/logo copy.png` is the decoded path of a reference a feed wrote with the space unencoded.
                    "/logo.png", "/logo-copy.png", "/logo copy.png" -> "image/png" to PNG
                    "/logo.svg" -> "image/svg+xml; charset=utf-8" to SVG.toByteArray()
                    // A PNG labeled JPEG is stored as what it is.
                    "/logo-mislabeled.jpg" -> "image/jpeg" to PNG
                    // One byte over the cap, chunked, so only the body read can refuse it.
                    "/logo-huge.png" -> "image/png" to ByteArray(2 * 1024 * 1024 + 1).also { PNG.copyInto(it) }
                    // An HTML page where a logo should be.
                    "/decoy.png" -> "text/html" to "<html><body>moved</body></html>".toByteArray()
                    // An image type whose body is not an image.
                    "/decoy-script.png" -> "image/png" to "<script>alert(1)</script>".toByteArray()
                    else -> { exchange.sendResponseHeaders(404, -1); exchange.close(); return@createContext }
                }
                if (path == "/logo.png" && logoBroken) { exchange.sendResponseHeaders(503, -1); exchange.close(); return@createContext }
                exchange.responseHeaders.set("Content-Type", type)
                exchange.sendResponseHeaders(200, if (path == "/logo-huge.png") 0 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
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
                "/twin-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"twin-station\" tvg-chno=\"5\" group-title=\"US: News\",STATION\n$base/twin.m3u8\n#EXTINF:-1 tvg-id=\"twin-sports\" tvg-chno=\"12\" group-title=\"US: Sports\",Twin Sports\n$base/twin-sports.m3u8\n"
                "/ext-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"ext-station\" tvg-chno=\"7\",Extensionless\n$base/resolve?id=1\n"
                "/fake-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"fake-station\" tvg-chno=\"8\",Mislabeled\n$base/fake.m3u8\n"
                "/proxy-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"proxy-station\" tvg-chno=\"9\",Proxied\n$proxyStreamUrl\n"
                "/branded-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"logo-station\" tvg-chno=\"11\" tvg-logo=\"$logoUrl\" group-title=\"News\",Logo Station\n$base/master.m3u8\n"
                "/resolve" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nmedia.m3u8\n"
                "/guide.xml" -> guideXml(false)
                "/learn-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"learn-show\" tvg-chno=\"30\",Learn Show\n$base/learn-show.m3u8\n#EXTINF:-1 tvg-id=\"nfl-network\" tvg-chno=\"31\",NFL Network\n$base/nfl-network.m3u8\n"
                "/learn2-feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"show-tv\" tvg-chno=\"40\",Learn Show TV\n$base/show-tv.m3u8\n#EXTINF:-1 tvg-id=\"nfl-channel\" tvg-chno=\"41\",NFL Channel\n$base/nfl-channel.m3u8\n"
                "/learn-guide.xml" -> scheduleXml(mapOf("learn-show" to SHOW, "nfl-network" to listOf("NFL Total Access", "Good Morning Football", "NFL Films Presents", "Inside the NFL")))
                "/learn2-guide.xml" -> scheduleXml(mapOf("show-tv" to SHOW, "nfl-channel" to listOf("Classic Game", "Game Rewind", "Top 100 Players", "Classic Game")))
                "/master.m3u8" -> "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=100000,AUDIO=\"audio\"\nmedia.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200000\nloop\n"
                "/media.m3u8", "/audio.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n"
                "/key" -> "0123456789abcdef"
                else -> "fixture-segment"
            }
            exchange.responseHeaders.set("Content-Type", if (path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream")
            if (gzipEncoded && (path == "/key" || path == "/media.m3u8" || path == "/audio.m3u8")) {
                val compressed = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
                exchange.responseHeaders.set("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, compressed.size.toLong()); exchange.responseBody.use { it.write(compressed) }
                return@createContext
            }
            val bytes = text.toByteArray()
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
        val metadata = if (guideMetadata) {
            "<sub-title>Pilot &amp; Plans</sub-title><category lang=\"en\">Sports</category><episode-num system=\"xmltv_ns\">1.4.</episode-num>" +
                "<episode-num system=\"onscreen\">S02E05</episode-num><new/><rating system=\"VCHIP\"><value>TV-PG</value></rating>"
        } else {
            ""
        }
        append("<programme channel=\"station\" start=\"20261010080000 -0500\" stop=\"20261010090000 -0500\"><title>Morning</title>$metadata</programme>")
        append("<programme channel=\"other\" start=\"20261010080000 -0500\" stop=\"20261010090000 -0500\"><title>Elsewhere</title></programme>")
        append("</tv>")
    }
    /** Hourly programmes from 2026-10-10T13:00Z, one list of titles per channel. */
    private fun scheduleXml(channels: Map<String, List<String>>): String = buildString {
        append("<tv>")
        for ((channel, titles) in channels) titles.forEachIndexed { hour, title ->
            append("<programme channel=\"$channel\" start=\"202610101${3 + hour}0000 +0000\" stop=\"202610101${4 + hour}0000 +0000\"><title>$title</title></programme>")
        }
        append("</tv>")
    }
    companion object {
        private val SHOW = listOf("News at One", "Afternoon Show", "Matinee", "Evening Report")

        fun resource(name: String): ByteArray = Fixture::class.java.classLoader.getResourceAsStream(name)!!.use { it.readBytes() }

        /** A 1x1 opaque PNG. */
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
        /** An SVG with a script element, which the server must store and serve byte for byte and never run. */
        const val SVG = "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"><script>alert(1)</script><rect width=\"1\" height=\"1\"/></svg>"

        /** A PID 0x100 packet whose payload is the packet number. */
        fun tsPacket(number: Int): ByteArray = ByteArray(188) { index ->
            when (index) { 0 -> 0x47; 1 -> 0x01; 2 -> 0x00; 3 -> (0x10 or (number and 0x0F)).toByte(); else -> number.toByte() }
        }
    }
}
