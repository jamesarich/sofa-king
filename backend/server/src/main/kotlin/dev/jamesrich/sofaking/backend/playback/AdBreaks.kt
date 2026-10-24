package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.contract.AdRun
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException
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
        val scan = Scan(base)
        for (raw in text.lineSequence()) scan.read(raw.trim())
        return scan.parsed()
    }

    /** One pass over a media playlist's lines, in order. */
    private class Scan(private val base: URI) {
        private var sequence = 0L
        private var durationMs = 0L
        private var taggedAd = false
        private var date: Long? = null
        private var cueOpen = false
        private var pendingEnd: Long? = null
        private var cueIn = false
        private var amagiAd = false
        private val ranges = ArrayList<LongRange>()
        private val segments = ArrayList<Segment>()
        private val stitched = ArrayList<Boolean>()
        private val plannedEnd = HashMap<Int, Long>()

        fun read(line: String) {
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> date = instantMs(line.substringAfter(':'))

                line.startsWith("#EXTINF:") -> {
                    durationMs = seconds(line.substringAfter(':').substringBefore(',')) ?: 0L
                    taggedAd = AdHosts.wurl(line.substringAfter(',', ""))
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

                else -> segment(line)
            }
        }

        private fun segment(reference: String) {
            val uri = resolved(base, reference)
            val at = date
            uri?.let(AdHosts::amagi)?.let { amagiAd = it }
            val stitchedAd = taggedAd || (uri != null && AdHosts.stitched(uri, amagiAd))
            val ad = stitchedAd || cueOpen || (uri != null && AdHosts.flagged(uri)) ||
                (at != null && ranges.any { at in it })
            pendingEnd?.let { plannedEnd[segments.size] = it }
            segments += Segment(sequence + segments.size, durationMs, ad, uri?.toString() ?: reference, cueIn)
            stitched += stitchedAd
            pendingEnd = null
            cueIn = false
            taggedAd = false
            date = at?.plus(durationMs)
            durationMs = 0L
        }

        /**
         * The segments read. Plex alternates its own part segments with the stream's, so a stitched break reads ad,
         * part, ad: a single segment between two stitched ads (MediaTailor, Amagi, Ottera, Google or Wurl), with no
         * cue-in before it, belongs to the break.
         */
        fun parsed(): Parsed {
            for (index in 1 until segments.size - 1) {
                val segment = segments[index]
                val betweenStitched = stitched[index - 1] && stitched[index + 1]
                if (!segment.ad && !segment.cueIn && betweenStitched) segments[index] = segment.copy(ad = true)
            }
            return Parsed(segments, plannedEnd, cueOpen)
        }
    }

    /** [reference] resolved against [base]; null when it does not resolve. */
    private fun resolved(base: URI, reference: String): URI? = try {
        base.resolve(reference)
    } catch (_: IllegalArgumentException) {
        null
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
