package dev.jamesrich.sofaking.backend.providers

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.Duration

/**
 * The Roku Channel's live TV. The channel list is Matt Huisman's i.mjh.nz mirror, which carries only the channels
 * that play without DRM, and its XMLTV is the provider's implicit guide. A resolve runs the therokuchannel.roku.com
 * web client's own steps: an anonymous session (cookies and a csrf token), the content proxy for the channel's
 * `playId`, and the `playback` API, which answers an OSM HLS URL.
 */
class RokuClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "roku"

    private val channelsUrl = context.options["channelsUrl"] ?: CHANNELS_URL
    private val webBase = context.options["webBase"] ?: WEB_BASE
    private val contentBase = context.options["contentBase"] ?: CONTENT_BASE

    private class Session(val cookie: String, val csrf: String)

    override suspend fun channels(): List<ProviderChannel> = listed()

    override suspend fun resolve(channelId: String): ResolvedStream {
        if (listed().none { it.id == channelId }) {
            context.invalidate(CHANNELS)
            if (listed().none {
                    it.id == channelId
                }
            ) {
                throw ProviderFailure("Channel is no longer listed", transient = false)
            }
        }
        val playId = context.cached("play:$channelId", PLAY_ID_LIFETIME) {
            val proxy = "$webBase/api/v2/homescreen/content/${URLEncoder.encode(
                contentBase + channelId,
                Charsets.UTF_8,
            )}"
            val content = session { context.http.get(proxy, apiHeaders(it)) }.require("Roku content").json()
            content["viewOptions"][0].str("playId")
                ?: throw ProviderFailure("Roku content has no playId", transient = false)
        }
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

    private suspend fun listed(): List<ProviderChannel> = context.cached(CHANNELS, Duration.ofMinutes(30)) {
        fetchChannels()
    }

    private suspend fun fetchChannels(): List<ProviderChannel> {
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
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result
    }

    /** Runs [request] in the stored web session; a 401 or 403 boots a new session and tries once more. */
    private suspend fun session(request: suspend (Session) -> Fetched): Fetched {
        val first = request(current())
        if (first.status != 401 && first.status != 403) return first
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
