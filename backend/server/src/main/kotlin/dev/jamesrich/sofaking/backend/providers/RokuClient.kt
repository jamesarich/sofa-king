package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.runCatchingCancellable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.Duration

/**
 * The Roku Channel's live TV. The channel list is Matt Huisman's i.mjh.nz mirror, which carries only the channels
 * that play without DRM, and its XMLTV is the provider's implicit guide. When the mirror fails, the list is the web
 * client's own station list, narrowed to the channels the mirror last listed, and when that fails too, the last list
 * that loaded stands in. A resolve runs the therokuchannel.roku.com web client's own steps: an anonymous session
 * (cookies and a csrf token), the content proxy for the channel's `playId`, and the `playback` API, which answers an
 * OSM HLS URL.
 */
class RokuClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "roku"

    private val channelsUrl = context.options["channelsUrl"] ?: CHANNELS_URL
    private val webBase = context.options["webBase"] ?: WEB_BASE
    private val contentBase = context.options["contentBase"] ?: CONTENT_BASE

    private class Session(val cookie: String, val csrf: String)

    override suspend fun channels(): List<ProviderChannel> = listed()

    override suspend fun resolve(channelId: String): ResolvedStream {
        context.findListed(CHANNELS) { listed().firstOrNull { it.id == channelId } }
        val playId = context.cached("play:$channelId", PLAY_ID_LIFETIME) { playId(channelId) }
        val body = buildJsonObject {
            put("rokuId", channelId)
            put("playId", playId)
            put("mediaFormat", "m3u")
            put("drmType", "widevine")
            put("quality", "fhd")
            put("bifUrl", JsonNull)
            put("adPolicyId", "")
            put("providerId", "rokuavod")
            put(
                "playbackContextParams",
                "sessionId=${context.deviceId()}&pageId=trc-us-live-ml-page-en-current&isNewSession=0&idType=roku-trc",
            )
        }.toString()
        val answer = session {
            context.http.post("$webBase/api/v3/playback", apiHeaders(it), body)
        }.require("Roku playback").json()
        if (answer["drm"] != null) throw ProviderFailure("Roku channel needs DRM", transient = false)
        val url = answer.str("url") ?: throw ProviderFailure("Roku playback answered no URL")
        return ResolvedStream(url, STREAM_HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun playId(channelId: String): String {
        val proxy = "$webBase/api/v2/homescreen/content/${URLEncoder.encode(contentBase + channelId, Charsets.UTF_8)}"
        val content = session { context.http.get(proxy, apiHeaders(it)) }.require("Roku content").json()
        return content["viewOptions"][0].str("playId")
            ?: throw ProviderFailure("Roku content has no playId", transient = false)
    }

    private suspend fun listed(): List<ProviderChannel> = context.cached(CHANNELS, CACHED_FOR) {
        fetchChannels()
    }

    private suspend fun fetchChannels(): List<ProviderChannel> {
        val mirrored = runCatchingCancellable { mirrorChannels() }
        // The mirror's list, or the last one it answered: the channels that play without DRM, with its groups.
        val known = runCatchingCancellable { context.lastListed(MIRRORED, mirrored) }
        if (mirrored.isSuccess) return mirrored.getOrThrow()
        return runCatchingCancellable { stations(known.getOrNull()) }.getOrElse { failure ->
            known.getOrElse { throw failure }
        }
    }

    /**
     * The web client's live stations with the groups of its guide's categories. With [known], only the channels it
     * lists, under its groups, since the station list does not say which channels need DRM.
     */
    private suspend fun stations(known: List<ProviderChannel>?): List<ProviderChannel> {
        val deviceId = context.deviceId()
        val epg = session {
            context.http.get("$webBase/api/v4/epg", apiHeaders(it) + ("x-roku-device-id" to deviceId))
        }.require("Roku station list").json()
        val groups = HashMap<String, String>()
        for (category in epg["categoryMapping"].array.filter { it.str("id") !in UNTHEMED }) {
            val title = category.str("title") ?: continue
            category["collectionIds"].array.mapNotNull { it.string }.forEach { groups.putIfAbsent(it, title) }
        }
        val knownGroups = known?.associate { it.id to it.group }
        val result = epg["collections"].array.map { it["features"]["station"] }.filter { station ->
            station["meta"].str("mediaType") == "livefeed" && station["viewOptions"][0].str("providerId") == PROVIDER
        }.mapNotNull { station ->
            val id = station["meta"].str("id")?.takeIf { knownGroups == null || it in knownGroups }
            val name = station.str("title")
            if (id == null || name == null) return@mapNotNull null
            ProviderChannel(
                id,
                name,
                station.str("displayNumber"),
                httpOrNull(station["imageMap"]["liveHudLogo"].str("path")),
                knownGroups?.get(id) ?: groups[id],
                listOf("us"),
            )
        }.sortedBy { it.number?.toLongOrNull() ?: Long.MAX_VALUE }
        if (result.isEmpty()) throw ProviderFailure("Roku station list is empty")
        return result
    }

    private suspend fun mirrorChannels(): List<ProviderChannel> {
        val channels = context.http.get(channelsUrl, MIRROR_HEADERS).require("Roku channel list").json()["channels"].obj
            ?: throw ProviderFailure("Roku channel list has no channels")
        val result = channels.entries.mapNotNull { (id, channel) ->
            val name = channel.str("name") ?: return@mapNotNull null
            ProviderChannel(
                id,
                name,
                channel["chno"].long?.toString(),
                httpOrNull(channel.str("logo")),
                channel["groups"][0].string,
                listOf("us"),
            )
        }.sortedBy { it.number?.toLongOrNull() ?: Long.MAX_VALUE }
        requireChannels(result)
        return result
    }

    /** Runs [request] in the stored web session; a 401 or 403 boots a new session and tries once more. */
    private suspend fun session(request: suspend (Session) -> Fetched): Fetched {
        val first = request(current())
        if (!first.refused) return first
        context.clearState(SESSION)
        return request(current())
    }

    private suspend fun current(): Session {
        context.state(SESSION)?.let { stored ->
            stored.split('\n').takeIf { it.size == 2 }?.let { return Session(it[0], it[1]) }
        }
        val cookies = LinkedHashMap<String, String>()
        fun keep(fetched: Fetched) = fetched.cookies.forEach { cookies[it.substringBefore('=')] = it }
        keep(context.http.get("$webBase/", PAGE_HEADERS).require("Roku home"))
        val csrfAnswer = context.http.get(
            "$webBase/api/v1/csrf",
            STREAM_HEADERS + ("Cookie" to cookies.values.joinToString("; ")),
        ).require("Roku csrf")
        keep(csrfAnswer)
        val csrf = csrfAnswer.json().str("csrf") ?: throw ProviderFailure("Roku answered no csrf token")
        val session = Session(cookies.values.joinToString("; "), csrf)
        context.saveState(SESSION, "${session.cookie}\n${session.csrf}", context.clock.instant().plus(SESSION_LIFETIME))
        return session
    }

    private fun apiHeaders(session: Session) = STREAM_HEADERS + mapOf(
        "Accept" to "application/json",
        "Cookie" to session.cookie,
        "csrf-token" to session.csrf,
        "Origin" to webBase,
        "Referer" to "$webBase/",
        "x-roku-reserved-lat" to "0",
    )

    companion object {
        private const val CHANNELS_URL = "https://i.mjh.nz/Roku/.channels.json.gz"
        private const val WEB_BASE = "https://therokuchannel.roku.com"
        private const val CONTENT_BASE = "https://content.sr.roku.com/content/v1/roku-trc/"
        private const val CHANNELS = "channels"
        private const val MIRRORED = "mirrored"
        private const val PROVIDER = "rokuavod"

        /** Guide categories that are not a genre: every channel, the personal rows and the catch-alls. */
        private val UNTHEMED = setOf(
            "roku.epg.category-allchannels",
            "roku.epg.cat-epg-recommended",
            "roku.epg.category-favorites",
            "roku.epg.category-recents",
            "roku.epg.category-hidden",
            "roku.epg.cat-epg-newly-added",
            "roku.epg.cat-livefeed-tv",
        )

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(30)
        private const val SESSION = "session"
        private val SESSION_LIFETIME = Duration.ofMinutes(55)
        private val PLAY_ID_LIFETIME = Duration.ofHours(6)
        private val URL_LIFETIME = Duration.ofHours(5)
        private val STREAM_HEADERS = mapOf("User-Agent" to BROWSER_USER_AGENT)
        private val PAGE_HEADERS =
            STREAM_HEADERS + ("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        private val MIRROR_HEADERS = mapOf("User-Agent" to "okhttp/4.12.0")
    }
}
