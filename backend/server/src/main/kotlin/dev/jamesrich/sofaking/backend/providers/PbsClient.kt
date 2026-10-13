package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * PBS member stations' clear livestreams, as pbs.org localizes them: the `zip` option (or explicit `stations`,
 * comma-separated station ids) picks the stations, each station's `livestream_feeds` names the feeds, and only
 * feeds with a `non_drm_url` are listed. The main channel of most stations is DRM and never appears. The guide
 * is the station's livestream schedule for today and tomorrow in its own time zone.
 */
class PbsClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "pbs"

    private val localizeBase = context.options["localizeBase"] ?: LOCALIZE_BASE
    private val siteBase = context.options["siteBase"] ?: SITE_BASE
    private val playerBase = context.options["playerBase"] ?: PLAYER_BASE

    private class Station(val id: String, val callSign: String, val zone: ZoneId, val logo: String?, val feeds: List<Feed>)
    private class Feed(val profile: String, val cid: String, val url: String)
    private class Listed(val channel: ProviderChannel, val station: Station, val feed: Feed)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }
        val programmes = ArrayList<GuideProgramme>()
        for (station in channels.filter { it.channel.id in wanted }.map { it.station }.distinctBy { it.id }) {
            val wantedFeeds = channels.filter { it.station.id == station.id && it.channel.id in wanted }.associateBy { it.feed.cid }
            val today = LocalDate.ofInstant(context.clock.instant(), station.zone)
            for (date in listOf(today, today.plusDays(1))) {
                val url = "$playerBase/api/livestream-schedule/${station.id}/?date=$date&timezone=${station.zone.id}"
                val payload = context.http.get(url, HEADERS).require("PBS schedule").json()
                for (collection in payload["collections"].array) for (block in collection["content"].array) for (channel in block["channels"].array) {
                    val listed = channel.str("feed_cid")?.let(wantedFeeds::get) ?: continue
                    for (item in channel["listings"].array) {
                        val start = item.str("start_time")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: continue
                        val seconds = item["duration"].long ?: continue
                        val show = item.str("show_title") ?: continue
                        if (seconds <= 0) continue
                        programmes += GuideProgramme(
                            listed.channel.id, start, start.plusSeconds(seconds), show, item.str("episode_description") ?: item.str("show_description"),
                            subTitle = item.str("episode_title"),
                        )
                    }
                }
            }
        }
        return ParsedGuide(index, programmes.distinctBy { "${it.channelId}|${it.startsAt}|${it.title}" })
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val listed = listed().firstOrNull { it.channel.id == channelId }
            ?: run { context.invalidate(STATIONS); listed().firstOrNull { it.channel.id == channelId } }
            ?: throw ProviderFailure("Feed is no longer listed", transient = false)
        return ResolvedStream(listed.feed.url, STREAM_HEADERS, context.clock.instant().plus(Duration.ofHours(1)))
    }

    private suspend fun listed(): List<Listed> = context.cached(STATIONS, Duration.ofMinutes(30)) { fetchStations() }

    private suspend fun fetchStations(): List<Listed> {
        val ids = LinkedHashSet<String>()
        context.options["stations"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.let(ids::addAll)
        val zip = context.options["zip"]?.trim()?.takeIf { it.matches(Regex("\\d{5}")) }
        if (zip != null) {
            val found = context.http.get("$localizeBase/localize/zipcode/$zip/", HEADERS).require("PBS localization").json()["stations"].array
            found.mapNotNull { it.str("pbs_id") }.forEach(ids::add)
        }
        if (ids.isEmpty()) throw ProviderFailure("No PBS station: set the zip or stations option", transient = false)
        val result = ArrayList<Listed>()
        for (id in ids) {
            val station = station(id) ?: continue
            for (feed in station.feeds) {
                val label = LABELS[feed.profile] ?: feed.profile.removePrefix("ga-").replace('-', ' ').replaceFirstChar(Char::uppercase)
                val name = if (feed.profile == "ga-main" || feed.profile.startsWith("ga-local")) "${station.callSign} $label" else "PBS $label (${station.callSign})"
                result += Listed(ProviderChannel("${station.id}:${feed.profile}", name, null, station.logo, "PBS", listOf("us")), station, feed)
            }
        }
        if (result.isEmpty()) throw ProviderFailure("No clear PBS feeds for the configured stations")
        return result
    }

    private suspend fun station(id: String): Station? {
        val fetched = context.http.get("$siteBase/api/station/$id/stations-list/", HEADERS)
        if (!fetched.ok) return null
        val attributes = fetched.json()["stationData"]["attributes"]
        val callSign = attributes.str("call_sign") ?: id
        val zone = runCatching { ZoneId.of(attributes.str("timezone") ?: "America/New_York") }.getOrDefault(ZoneId.of("America/New_York"))
        val images = attributes["images"].array
        val logo = httpOrNull(images.firstOrNull { it.str("profile") == "color-logo" }.str("url") ?: images.firstOrNull { it.str("profile") == "white-logo" }.str("url"))
        val feeds = attributes["livestream_feeds"].array.mapNotNull { feed ->
            val profile = feed.str("profile") ?: return@mapNotNull null
            val url = httpOrNull(feed.str("non_drm_url")) ?: return@mapNotNull null
            Feed(profile, feed.str("associated_tvss_feed") ?: feed.str("cid") ?: profile, url)
        }
        return Station(id, callSign, zone, logo, feeds)
    }

    companion object {
        private const val LOCALIZE_BASE = "https://localization.services.pbs.org"
        private const val SITE_BASE = "https://www.pbs.org"
        private const val PLAYER_BASE = "https://player.pbs.org"
        private const val STATIONS = "stations"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        private val STREAM_HEADERS = mapOf("User-Agent" to USER_AGENT, "Origin" to SITE_BASE, "Referer" to "$SITE_BASE/")
        private val HEADERS = STREAM_HEADERS + ("Accept" to "application/json")
        private val LABELS = mapOf(
            "ga-create" to "Create", "ga-fnx" to "FNX", "ga-local-subchannel-1" to "Local 1", "ga-local-subchannel-2" to "Local 2",
            "ga-main" to "Main", "ga-nhk" to "NHK World", "ga-world" to "World", "kids-main" to "Kids", "ga-france-24" to "France 24",
        )
    }
}
