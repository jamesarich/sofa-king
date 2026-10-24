package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.runCatchingCancellable
import java.time.Duration

/**
 * Broadcasters' own free live feeds: a fixed table of clear HLS URLs on each broadcaster's CDN, offered without an
 * account or a token. Nothing is fetched to list them. A resolve is the table entry, except for the CBS News
 * streams, whose ad-stitched event URL is read from the station's live page on cbsnews.com because the event id
 * changes now and then; the table's URL is the fallback when the page does not name one. Like `hallmark`, the
 * URLs are hard-coded and break silently when a broadcaster moves a feed, so the probes and source health watch them.
 */
class DirectClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "direct"

    /** A base that replaces every feed's URL with `BASE/ID.m3u8`, for tests. */
    private val streamBase = context.options["streamBase"]
    private val pageBase = context.options["pageBase"] ?: PAGE_BASE

    private class Feed(val id: String, val name: String, val group: String, val region: String, val url: String, val page: String? = null)

    override suspend fun channels(): List<ProviderChannel> =
        FEEDS.mapIndexed { index, feed -> ProviderChannel(feed.id, feed.name, (FIRST_NUMBER + index).toString(), null, feed.group, listOf(feed.region)) }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val feed = FEEDS.firstOrNull { it.id == channelId } ?: throw ProviderFailure("Unknown direct feed", transient = false)
        val url = streamBase?.let { "${it.trimEnd('/')}/${feed.id}.m3u8" } ?: feed.page?.let { current(feed, it) } ?: feed.url
        return ResolvedStream(url, HEADERS)
    }

    /** The event URL the station's live page plays now, else the table's URL; one page read per feed per six hours. */
    private suspend fun current(feed: Feed, page: String): String = context.cached("page:${feed.id}", PAGE_TTL) {
        val html = runCatchingCancellable { context.http.get(pageBase + page, HEADERS).require("CBS News page").text() }.getOrNull()
        html?.let { EVENT_URL.find(it)?.value } ?: feed.url
    }

    companion object {
        private const val FIRST_NUMBER = 9101
        private const val PAGE_BASE = "https://www.cbsnews.com"
        private val PAGE_TTL = Duration.ofHours(6)
        private val HEADERS = mapOf("User-Agent" to "Mozilla/5.0")
        private val EVENT_URL = Regex("""https://dai\.google\.com/linear/hls/event/[A-Za-z0-9_-]+/master\.m3u8""")

        private fun cbs(id: String, name: String, market: String, fallback: String) = Feed(id, name, "News", "us", fallback, "/$market/live/")

        /** The feeds in channel-number order. Each URL is the one the broadcaster's own site or app plays. */
        private val FEEDS = listOf(
            Feed("cbs-news-247", "CBS News 24/7", "News", "us", "https://cbsn-us.cbsnstream.cbsnews.com/out/v1/55a8648e8f134e82a470f83d562deeca/master.m3u8", "/live/"),
            cbs("cbs-news-baltimore", "CBS News Baltimore", "baltimore", "https://dai.google.com/linear/hls/event/TMUt418sQMeu1QtneYJ9ag/master.m3u8"),
            cbs("cbs-news-bay-area", "CBS News Bay Area", "sanfrancisco", "https://dai.google.com/linear/hls/event/laLJwH1nS2KQAUQTF8z1Cg/master.m3u8"),
            cbs("cbs-news-boston", "CBS News Boston", "boston", "https://dai.google.com/linear/hls/event/dHA193vxQ_WqZNVoC-W5MA/master.m3u8"),
            cbs("cbs-news-chicago", "CBS News Chicago", "chicago", "https://dai.google.com/linear/hls/event/_xQBtL7ZSEClkNebaXzn8w/master.m3u8"),
            cbs("cbs-news-colorado", "CBS News Colorado", "colorado", "https://dai.google.com/linear/hls/event/E9CkyheTSKeGiOiICU_JBQ/master.m3u8"),
            cbs("cbs-news-detroit", "CBS News Detroit", "detroit", "https://dai.google.com/linear/hls/event/xnyAMOsfSV2J1b-A3nROFQ/master.m3u8"),
            cbs("cbs-news-los-angeles", "CBS News Los Angeles", "losangeles", "https://dai.google.com/linear/hls/event/K_2aA7OVRTacJJli1oSi8w/master.m3u8"),
            cbs("cbs-news-miami", "CBS News Miami", "miami", "https://dai.google.com/linear/hls/event/_yODrBHESGSzPe3dqW4gGg/master.m3u8"),
            cbs("cbs-news-minnesota", "CBS News Minnesota", "minnesota", "https://dai.google.com/linear/hls/event/c0XucO32RNKG8oeQ351a4Q/master.m3u8"),
            cbs("cbs-news-new-york", "CBS News New York", "newyork", "https://dai.google.com/linear/hls/event/jeTDC610T-OSPdiWhcl0Pg/master.m3u8"),
            cbs("cbs-news-philadelphia", "CBS News Philadelphia", "philadelphia", "https://dai.google.com/linear/hls/event/R2Kw0uvnQIW7fUmOseurzA/master.m3u8"),
            cbs("cbs-news-pittsburgh", "CBS News Pittsburgh", "pittsburgh", "https://dai.google.com/linear/hls/event/mir-uZYjTu6-8JLMHBTKhQ/master.m3u8"),
            cbs("cbs-news-sacramento", "CBS News Sacramento", "sacramento", "https://dai.google.com/linear/hls/event/e7mp4YVGS7O_e7rqRNjKow/master.m3u8"),
            cbs("cbs-news-texas", "CBS News Texas", "texas", "https://dai.google.com/linear/hls/event/ogzLhRCkRbG3lFHHJwqaNw/master.m3u8"),
            Feed("bloomberg-tv", "Bloomberg TV", "News", "us", "https://www.bloomberg.com/media-manifest/streams/us.m3u8"),
            Feed("bloomberg-originals", "Bloomberg Originals", "News", "us", "https://www.bloomberg.com/media-manifest/streams/qt.m3u8"),
            Feed("cheddar", "Cheddar", "News", "us", "https://hls.livecdn.io/cheddar.com/cheddar/playlist.m3u8"),
            Feed("newsmax", "Newsmax", "News", "us", "https://nmx1ota.akamaized.net/hls/live/2107010/Live_1/index.m3u8"),
            Feed("fox-weather", "Fox Weather", "Weather", "us", "https://247wlive.foxweather.com/stream/index.m3u8"),
            Feed("al-jazeera-english", "Al Jazeera English", "News", "qa", "https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8"),
            Feed("dw-english", "DW English", "News", "de", "https://dwamdstream103.akamaized.net/hls/live/2015526/dwstream103/master.m3u8"),
            Feed("france-24-english", "France 24 English", "News", "fr", "https://live.france24.com/hls/live/2037218-b/F24_EN_HI_HLS/master_5000.m3u8"),
            Feed("nhk-world-japan", "NHK World-Japan", "News", "jp", "https://masterpl.hls.nhkworld.jp/hls/w/live/smarttv.m3u8"),
            Feed("abc-news-australia", "ABC News (Australia)", "News", "au", "https://abc-news-dmd-streams-1.akamaized.net/out/v1/701126012d044971b3fa89406a440133/index.m3u8"),
            Feed("i24news-english", "i24NEWS English", "News", "il", "https://i24newsenglish-cdn.encoders.immergo.tv/master.m3u8"),
            Feed("dw-deutsch", "DW Deutsch", "News", "de", "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8"),
            Feed("das-erste", "Das Erste", "Entertainment", "de", "https://daserste-live.ard-mcdn.de/daserste/live/hls/de/master.m3u8"),
            Feed("ard-alpha", "ARD alpha", "Documentary", "de", "https://mcdn.br.de/br/fs/ard_alpha/hls/de/master.m3u8"),
            Feed("france-24-french", "France 24 French", "News", "fr", "https://live.france24.com/hls/live/2037179-b/F24_FR_HI_HLS/master_5000.m3u8"),
            Feed("rtve-la-1", "La 1", "Entertainment", "es", "https://rtvelivestream.rtve.es/rtvesec/la1/la1_main.m3u8"),
            Feed("rtve-24h", "Canal 24 Horas", "News", "es", "https://rtvelivestream.rtve.es/rtvesec/24h/24h_main.m3u8"),
            Feed("3cat-info", "3CatInfo", "News", "es", "https://directes-tv-int.3catdirectes.cat/live-origin/canal324-hls/master.m3u8"),
            Feed("euronews", "Euronews", "News", "fr", "https://cdn-euronews.akamaized.net/live/eds/euronews-en/25002/index.m3u8"),
            Feed("africanews", "Africanews", "News", "fr", "https://cdn-euronews.akamaized.net/live/eds/africanews-en/25049/index.m3u8"),
            Feed("trt-world", "TRT World", "News", "tr", "https://tv-trtworld.medya.trt.com.tr/master.m3u8"),
            Feed("al-jazeera-arabic", "Al Jazeera Arabic", "News", "qa", "https://live-hls-web-aja.getaj.net/AJA/index.m3u8"),
            Feed("al-jazeera-mubasher", "Al Jazeera Mubasher", "News", "qa", "https://live-hls-web-ajm.getaj.net/AJM/index.m3u8"),
            Feed("cna", "CNA", "News", "sg", "https://d2e1asnsl7br7b.cloudfront.net/7782e205e72f43aeb4a48ec97f66ebbe/index.m3u8"),
            Feed("arirang", "Arirang TV", "Entertainment", "kr", "https://amdlive-ch01-ctnd-com.akamaized.net/arirang_1ch/smil:arirang_1ch.smil/playlist.m3u8"),
            Feed("cgtn", "CGTN", "News", "cn", "https://news.cgtn.com/resource/live/english/cgtn-news.m3u8"),
        )
    }
}
