package dev.jamesrich.sofaking.backend.playback

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.zip.GZIPInputStream

/** What a segment's absolute URL, or its title, says about it, by the service that stitched or flagged it. */
internal object AdHosts {
    /**
     * True for a segment an ad server stitched in: MediaTailor, Ottera, Google's pods, or Amagi's playout while its
     * last beacon was an ad ([amagiAd]).
     */
    fun stitched(uri: URI, amagiAd: Boolean): Boolean =
        mediaTailor(uri) || (amagiAd && onAmagi(uri)) || ottera(uri) || daiPod(uri)

    /** True for a segment its origin flags as an ad: Pluto's muxer and bumpers, Roku's ad hosts, fuelmedia's tags. */
    fun flagged(uri: URI): Boolean = pluto(uri) || roku(uri) || FuelSegments.ad(uri)

    /**
     * Wurl's own stitcher (`*.wurl.tv`, as on Plex) labels every segment in its `#EXTINF` title, colon-separated:
     * `epid=…:ct=M:tag=ad:aid=…` for an ad and `tag=content` otherwise; its picture-in-picture overlay opportunities
     * (`#EXT-X-TRANSMIT-CUE-OUT:AdFormat=pip`) leave the show playing and are not breaks.
     */
    fun wurl(title: String): Boolean = "tag=ad" in title && title.split(':', ',').any { it.trim() == "tag=ad" }

    /**
     * Amagi's ad insertion serves segments through a beacon URL on its `*.amagi.tv` playout,
     * `/<node>/beacon/<channel>/<hash>?…&media_type=M&…`, where `media_type` is `M` for an ad and `C` for the channel's
     * own content: true, false, or null for any other segment. Seen on 2026-10-23 on Whale's Qwest TV and on Plex's
     * Amagi channels as runs of 2 to 21 six-second `M` segments. Once a player has fetched a beacon, later playlists
     * serve most segments straight from the playout (`/playlist<n>_<n>.ts`), keeping a beacon on every third segment
     * of a break (`M`, plain, plain, `M`), so a plain segment on the playout is whatever the beacon before it was.
     */
    fun amagi(uri: URI): Boolean? {
        if (!onAmagi(uri) || uri.rawPath?.contains("/beacon/") != true) return null
        return uri.rawQuery.orEmpty().split('&').any { it == "media_type=M" }
    }

    private fun onAmagi(uri: URI): Boolean = uri.host?.lowercase()?.endsWith(".amagi.tv") == true

    /**
     * MediaTailor serves the ads it stitches through its segment route,
     * `/v1/segment/<hash>/<configuration>/<session>/<n>/<sequence>` on `*.mediatailor.<region>.amazonaws.com` or a CDN
     * in front of it (Xumo's NBCU channels drop the `/v1`); the show stays on its origin. Seen on 2026-10-22 as 60 to
     * 120 s runs after a discontinuity.
     */
    private val MEDIATAILOR_SEGMENT =
        Regex(
            """^(?:/v1)?/segment/[0-9a-f]{32,}/[^/]+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/""" +
                """\d+/\d+$""",
        )

    private fun mediaTailor(uri: URI): Boolean = uri.rawPath?.let(MEDIATAILOR_SEGMENT::matches) == true

    /**
     * Ottera stitches its breaks from `stream.ads.ottera.tv` (`/mf2/<rendition>?u=<creative>&…&d=<break
     * seconds>`) between its channels' own segments on `*.otteravision.com`. Seen on 2026-10-23 on
     * Plex's Bassmaster as a 120 s break.
     */
    private fun ottera(uri: URI): Boolean = uri.host?.lowercase()?.endsWith(".ads.ottera.tv") == true

    /**
     * Google Ad Manager's dynamic ad insertion serves each break's pod, ads and slate alike, from
     * `dai.google.com/linear/pods/…` while the show plays from the publisher's CDN. Seen on 2026-10-23 on Plex's 48
     * Hours and Samsung's CBS News, inside the playlists' own `#EXT-X-CUE-OUT` and `#EXT-X-CUE-IN`.
     */
    private fun daiPod(uri: URI): Boolean =
        uri.host?.lowercase() == "dai.google.com" && uri.rawPath?.startsWith("/linear/pods/") == true

    /**
     * Roku's live channels play from `gcp-live-*.delivery.roku.com` and stitch their breaks from elsewhere: house
     * promos and some spots from `osm-ads*.delivery.roku.com` (`…/hls/transcode_OVP_SSAI_MANAGER_…`), other spots from
     * `osm-<region>.sr.roku.com/osbt/…`. Seen on 2026-10-23 on eight channels as 30 to 160 s runs, all of them breaks.
     */
    private fun roku(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (!host.endsWith(".roku.com")) return false
        return host.startsWith("osm-ads") || (host.startsWith("osm-") && uri.rawPath?.startsWith("/osbt/") == true)
    }

    /**
     * Pluto's ad muxer serves stitched creatives under `/v1/mp4/…` and `/mp4/v1/…`, and some creatives come straight
     * from their store, `/<n>_ad/creative/…`, the same path the muxer wraps; its own bumpers are `…adbumper…` clips.
     */
    private val PLUTO_MUXER = Regex("""^/(?:v1/mp4|mp4/v1)/""")
    private val PLUTO_CREATIVE = Regex("""^/\d+_ad/creative/""")
    private val PLUTO_BUMPER = Regex("""ad_?bumper""", RegexOption.IGNORE_CASE)

    /** True for a Pluto segment from its ad muxer or a bumper clip; other hosts never match, whatever their paths. */
    private fun pluto(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (!host.endsWith(".plutotv.net") && !host.endsWith(".pluto.tv")) return false
        val path = uri.rawPath ?: return false
        return PLUTO_MUXER.containsMatchIn(path) || PLUTO_CREATIVE.containsMatchIn(path) ||
            ("_Pluto_TV_OandO/" in path && PLUTO_BUMPER.containsMatchIn(path))
    }
}

/**
 * The fuelmedia.io origin behind some Stirr stations serves its decorated segments as
 * `/play/seg/<variant>/<token>.ts`, where the token is base64url-encoded gzip of a JSON array whose first entry's
 * `SegTag` says what the segment is: `IsAdBreak`, `IsAd` and `IsAdFiller` (an unsold slot's filler) mark a break.
 * Seen on 2026-10-23 on KOTV as runs of 31 to 35 `adFiller` segments.
 */
private object FuelSegments {
    private val SEGMENT = Regex("""^/play/seg/[^/]+/([A-Za-z0-9_-]+)(?:\.ts)?$""")

    private const val TOKEN_LIMIT = 64 * 1024
    private val AD_TAGS = listOf("IsAdBreak", "IsAd", "IsAdFiller")

    /** True for a fuelmedia.io segment whose token tags it as part of a break. */
    fun ad(uri: URI): Boolean {
        if (uri.host?.lowercase()?.endsWith(".fuelmedia.io") != true) return false
        val token = SEGMENT.find(uri.rawPath ?: return false)?.groupValues?.get(1) ?: return false
        val tag = segTag(token) ?: return false
        return AD_TAGS.any { (tag[it] as? JsonPrimitive)?.booleanOrNull == true }
    }

    /** The `SegTag` of the token's first entry; null for a token that does not decode. */
    private fun segTag(token: String): JsonObject? = try {
        val gzip = Base64.getUrlDecoder().decode(token.padEnd((token.length + 3) / 4 * 4, '='))
        val text = GZIPInputStream(ByteArrayInputStream(gzip)).use { it.readNBytes(TOKEN_LIMIT) }.decodeToString()
        ((Json.parseToJsonElement(text) as? JsonArray)?.firstOrNull() as? JsonObject)?.get("SegTag") as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    }
}
