package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.contract.AdRun
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.roundToLong

/**
 * The ad segments of a live HLS media playlist, the way the free streaming services mark them: SCTE-35 cue tags
 * (`#EXT-X-CUE-OUT`, `-CUE-OUT-CONT`, `-CUE-IN`, as Tubi, Whale and LG send), `#EXT-X-DATERANGE` with `SCTE35-OUT` in
 * a dated playlist, Pluto's stitched ads, which come from its ad muxer or its bumper clips, the ads AWS MediaTailor
 * stitches for Xumo and Plex, the ads and promos Roku stitches from its ad servers, Ottera's ad host, Google's ad
 * pods, Wurl's `tag=ad` segment titles, and the flags two origins write into their segment URLs: Amagi's ad insertion
 * (Whale, Plex and other Amagi channels) and the fuelmedia.io origin behind some Stirr stations. Nothing is guessed
 * from the picture, and a `#EXT-X-DISCONTINUITY` alone means nothing: Pluto puts one between episodes too. A cue whose
 * `#EXT-X-CUE-OUT` has left the window is carried on by [AdClock], which sees every refresh.
 */
internal object AdBreaks {
    /**
     * One media segment: its media sequence number, length, whether it is an ad, its absolute URL, and whether a
     * `#EXT-X-CUE-IN` comes before it in this window.
     */
    data class Segment(
        val sequence: Long,
        val durationMs: Long,
        val ad: Boolean,
        val url: String = "",
        val cueIn: Boolean = false,
    )

    private val ATTRIBUTE = Regex("""([A-Za-z0-9-]+)=("[^"]*"|[^,]*)""")

    /**
     * Pluto's ad muxer serves stitched creatives under `/v1/mp4/…` and `/mp4/v1/…`, and some creatives come straight
     * from their store, `/<n>_ad/creative/…`, the same path the muxer wraps; its own bumpers are `…adbumper…` clips.
     */
    private val PLUTO_MUXER = Regex("""^/(?:v1/mp4|mp4/v1)/""")
    private val PLUTO_CREATIVE = Regex("""^/\d+_ad/creative/""")
    private val PLUTO_BUMPER = Regex("""ad_?bumper""", RegexOption.IGNORE_CASE)

    /**
     * [text] with a [AdRun.TAG] line for each run of ad segments it holds; a playlist with none, or a
     * multivariant one, comes back as it was.
     */
    fun annotate(text: String, base: URI): String = annotate(text, runs(text, base))

    /** [text] with a [AdRun.TAG] line for each of [runs]. */
    fun annotate(text: String, runs: List<AdRun>): String {
        if (runs.isEmpty()) return text
        return text.trimEnd('\n', '\r') + "\n" + runs.joinToString("") { it.tag() + "\n" }
    }

    /** The runs of ad segments in [text], in order; empty for a multivariant playlist. */
    fun runs(text: String, base: URI): List<AdRun> = parse(text, base)?.let(::runs).orEmpty()

    /** The runs of ad segments in [parsed], in order. */
    fun runs(parsed: Parsed): List<AdRun> {
        val runs = ArrayList<AdRun>()
        var index = 0
        while (index < parsed.segments.size) {
            if (!parsed.segments[index].ad) {
                index++
                continue
            }
            val first = index
            while (index < parsed.segments.size && parsed.segments[index].ad) index++
            val segments = parsed.segments
            runs += AdRun(segments[first].sequence, segments[index - 1].sequence, parsed.plannedEndMs[first])
        }
        return runs
    }

    /**
     * A parsed media playlist: its segments, the planned end of a break, in ms after the segment it begins at, by
     * segment index, and whether a cue is still open after the last segment.
     */
    class Parsed(val segments: List<Segment>, val plannedEndMs: Map<Int, Long>, val cueOpenAtEnd: Boolean = false)

    /** Reads [text], resolving segment URIs against [base]; null for a multivariant playlist, which has no segments. */
    fun parse(text: String, base: URI): Parsed? {
        if (!text.contains("#EXTINF")) return null
        var sequence = 0L
        var durationMs = 0L
        var taggedAd = false
        var date: Long? = null
        var cueOpen = false
        var pendingEnd: Long? = null
        var cueIn = false
        var amagiAd = false
        val ranges = ArrayList<LongRange>()
        val segments = ArrayList<Segment>()
        val stitched = ArrayList<Boolean>()
        val plannedEnd = HashMap<Int, Long>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence =
                        line.substringAfter(':').trim().toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> date = instantMs(line.substringAfter(':'))

                line.startsWith("#EXTINF:") -> {
                    durationMs = seconds(line.substringAfter(':').substringBefore(',')) ?: 0L
                    taggedAd = wurl(line.substringAfter(',', ""))
                }

                line.startsWith("#EXT-X-CUE-OUT-CONT") -> {
                    // Each ad segment carries one; when the CUE-OUT has left the window, the
                    // first is where the break shows.
                    val (elapsed, planned) = contValues(line.substringAfter(':', ""))
                    if (!cueOpen && planned != null) pendingEnd = (planned - (elapsed ?: 0L)).coerceAtLeast(0)
                    cueOpen = true
                }

                line.startsWith("#EXT-X-CUE-OUT") -> {
                    pendingEnd = seconds(line.substringAfter(':', "").substringAfter("DURATION=").substringBefore(','))
                    cueOpen = true
                }

                line.startsWith("#EXT-X-CUE-IN") -> {
                    cueOpen = false
                    pendingEnd = null
                    cueIn = true
                }

                line.startsWith("#EXT-X-DATERANGE:") -> dateRange(line.substringAfter(':'))?.let(ranges::add)

                line.startsWith("#") -> Unit

                else -> {
                    val at = date
                    amagi(base, line)?.let { amagiAd = it }
                    val stitchedAd =
                        taggedAd || mediaTailor(
                            base,
                            line,
                        ) || (amagiAd && amagiHost(base, line)) || ottera(base, line) || daiPod(base, line)
                    val ad =
                        stitchedAd || cueOpen || pluto(base, line) || roku(base, line) || fuelmedia(base, line) ||
                            (at != null && ranges.any { at in it })
                    if (pendingEnd != null) plannedEnd[segments.size] = pendingEnd
                    segments += Segment(sequence + segments.size, durationMs, ad, absolute(base, line), cueIn)
                    stitched += stitchedAd
                    pendingEnd = null
                    cueIn = false
                    taggedAd = false
                    date = at?.plus(durationMs)
                    durationMs = 0L
                }
            }
        }
        // Plex alternates its own part segments with the stream's, so a stitched break reads ad, part, ad: a single
        // segment between two stitched ads (MediaTailor, Amagi, Ottera, Google or Wurl), with no cue-in
        // before it, belongs to the break.
        for (index in 1 until segments.size - 1) {
            val segment = segments[index]
            val betweenStitched = stitched[index - 1] && stitched[index + 1]
            if (!segment.ad && !segment.cueIn && betweenStitched) {
                segments[index] = segment.copy(ad = true)
            }
        }
        return Parsed(segments, plannedEnd, cueOpen)
    }

    private fun absolute(base: URI, reference: String): String = try {
        base.resolve(reference).toString()
    } catch (_: IllegalArgumentException) {
        reference
    }

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

    /** True for a segment MediaTailor stitched in. */
    private fun mediaTailor(base: URI, reference: String): Boolean {
        val path =
            try {
                base.resolve(reference).rawPath
            } catch (_: IllegalArgumentException) {
                return false
            } ?: return false
        return MEDIATAILOR_SEGMENT.matches(path)
    }

    /**
     * Roku's live channels play from `gcp-live-*.delivery.roku.com` and stitch their breaks from elsewhere: house
     * promos and some spots from `osm-ads*.delivery.roku.com` (`…/hls/transcode_OVP_SSAI_MANAGER_…`), other spots from
     * `osm-<region>.sr.roku.com/osbt/…`. Seen on 2026-10-23 on eight channels as 30 to 160 s runs, all of them breaks.
     */
    private fun roku(base: URI, reference: String): Boolean {
        val uri = try {
            base.resolve(reference)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (!host.endsWith(".roku.com")) return false
        return host.startsWith("osm-ads") || (host.startsWith("osm-") && uri.rawPath?.startsWith("/osbt/") == true)
    }

    /**
     * Ottera stitches its breaks from `stream.ads.ottera.tv` (`/mf2/<rendition>?u=<creative>&…&d=<break
     * seconds>`) between its channels' own segments on `*.otteravision.com`. Seen on 2026-10-23 on
     * Plex's Bassmaster as a 120 s break.
     */
    private fun ottera(base: URI, reference: String): Boolean {
        val host =
            try {
                base.resolve(reference).host
            } catch (_: IllegalArgumentException) {
                return false
            } ?: return false
        return host.lowercase().endsWith(".ads.ottera.tv")
    }

    /**
     * Google Ad Manager's dynamic ad insertion serves each break's pod, ads and slate alike, from
     * `dai.google.com/linear/pods/…` while the show plays from the publisher's CDN. Seen on 2026-10-23 on Plex's 48
     * Hours and Samsung's CBS News, inside the playlists' own `#EXT-X-CUE-OUT` and `#EXT-X-CUE-IN`.
     */
    private fun daiPod(base: URI, reference: String): Boolean {
        val uri = try {
            base.resolve(reference)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return uri.host?.lowercase() == "dai.google.com" && uri.rawPath?.startsWith("/linear/pods/") == true
    }

    /**
     * Wurl's own stitcher (`*.wurl.tv`, as on Plex) labels every segment in its `#EXTINF` title, colon-separated:
     * `epid=…:ct=M:tag=ad:aid=…` for an ad and `tag=content` otherwise; its picture-in-picture overlay opportunities
     * (`#EXT-X-TRANSMIT-CUE-OUT:AdFormat=pip`) leave the show playing and are not breaks.
     */
    private fun wurl(title: String): Boolean = "tag=ad" in title && title.split(':', ',').any { it.trim() == "tag=ad" }

    /**
     * Amagi's ad insertion serves segments through a beacon URL on its `*.amagi.tv` playout,
     * `/<node>/beacon/<channel>/<hash>?…&media_type=M&…`, where `media_type` is `M` for an ad and `C` for the channel's
     * own content: true, false, or null for any other segment. Seen on 2026-10-23 on Whale's Qwest TV and on Plex's
     * Amagi channels as runs of 2 to 21 six-second `M` segments. Once a player has fetched a beacon, later playlists
     * serve most segments straight from the playout (`/playlist<n>_<n>.ts`), keeping a beacon on every third segment
     * of a break (`M`, plain, plain, `M`), so a plain segment on the playout is whatever the beacon before it was.
     */
    private fun amagi(base: URI, reference: String): Boolean? {
        val uri = try {
            base.resolve(reference)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (uri.host?.lowercase()?.endsWith(".amagi.tv") != true || uri.rawPath?.contains("/beacon/") != true) {
            return null
        }
        return uri.rawQuery.orEmpty().split('&').any { it == "media_type=M" }
    }

    private fun amagiHost(base: URI, reference: String): Boolean = try {
        base.resolve(reference).host?.lowercase()?.endsWith(".amagi.tv") == true
    } catch (_: IllegalArgumentException) {
        false
    }

    /**
     * The fuelmedia.io origin behind some Stirr stations serves its decorated segments as
     * `/play/seg/<variant>/<token>.ts`, where the token is base64url-encoded gzip of a JSON array whose first entry's
     * `SegTag` says what the segment is: `IsAdBreak`, `IsAd` and `IsAdFiller` (an unsold slot's filler) mark a break.
     * Seen on 2026-10-23 on KOTV as runs of 31 to 35 `adFiller` segments.
     */
    private val FUEL_SEGMENT = Regex("""^/play/seg/[^/]+/([A-Za-z0-9_-]+)(?:\.ts)?$""")

    private fun fuelmedia(base: URI, reference: String): Boolean {
        val uri = try {
            base.resolve(reference)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (uri.host?.lowercase()?.endsWith(".fuelmedia.io") != true) return false
        val token = FUEL_SEGMENT.find(uri.rawPath ?: return false)?.groupValues?.get(1) ?: return false
        val tag = try {
            val gzip = Base64.getUrlDecoder().decode(token.padEnd((token.length + 3) / 4 * 4, '='))
            val text = GZIPInputStream(ByteArrayInputStream(gzip)).use {
                it.readNBytes(FUEL_TOKEN_LIMIT)
            }.decodeToString()
            ((Json.parseToJsonElement(text) as? JsonArray)?.firstOrNull() as? JsonObject)?.get("SegTag") as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
            ?: return false
        return listOf("IsAdBreak", "IsAd", "IsAdFiller").any { (tag[it] as? JsonPrimitive)?.booleanOrNull == true }
    }

    private const val FUEL_TOKEN_LIMIT = 64 * 1024

    /** True for a Pluto segment from its ad muxer or a bumper clip; other hosts never match, whatever their paths. */
    private fun pluto(base: URI, reference: String): Boolean {
        val uri = try {
            base.resolve(reference)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (!host.endsWith(".plutotv.net") && !host.endsWith(".pluto.tv")) return false
        val path = uri.rawPath ?: return false
        return PLUTO_MUXER.containsMatchIn(path) || PLUTO_CREATIVE.containsMatchIn(path) ||
            ("_Pluto_TV_OandO/" in path && PLUTO_BUMPER.containsMatchIn(path))
    }

    /**
     * A `#EXT-X-DATERANGE` marking an SCTE-35 splice out, as the program-date span it covers;
     * open-ended ones are left alone.
     */
    private fun dateRange(attributes: String): LongRange? {
        val values = ATTRIBUTE.findAll(attributes).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        if (values["SCTE35-OUT"] == null) return null
        val start = values["START-DATE"]?.let(::instantMs) ?: return null
        val end = values["END-DATE"]?.let(::instantMs)
            ?: (values["DURATION"] ?: values["PLANNED-DURATION"])?.let(::seconds)?.let { start + it }
            ?: return null
        return start until end
    }

    /** `ElapsedTime=1.9,Duration=150` or the older `1.9/150`, as elapsed and planned milliseconds. */
    private fun contValues(value: String): Pair<Long?, Long?> {
        if ('/' in value && '=' !in value) {
            return seconds(value.substringBefore('/')) to seconds(value.substringAfter('/'))
        }
        val values = ATTRIBUTE.findAll(value).associate { it.groupValues[1].uppercase() to it.groupValues[2] }
        return seconds(values["ELAPSEDTIME"]) to seconds(values["DURATION"])
    }

    private fun seconds(value: String?): Long? = value?.trim()?.toDoubleOrNull()?.takeIf {
        it >= 0
    }?.let { (it * MS_PER_SECOND).roundToLong() }

    private fun instantMs(value: String): Long? = try {
        Instant.parse(value.trim()).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
