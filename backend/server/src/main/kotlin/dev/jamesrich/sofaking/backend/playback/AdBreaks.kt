package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.AdRun
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

/**
 * The ad segments of a live HLS media playlist, the way the free streaming services mark them: SCTE-35 cue tags
 * (`#EXT-X-CUE-OUT`, `-CUE-OUT-CONT`, `-CUE-IN`, as Tubi sends), `#EXT-X-DATERANGE` with `SCTE35-OUT` in a dated
 * playlist, and Pluto's stitched ads, which come from its ad muxer or its bumper clips. Nothing is guessed from the
 * picture, and a `#EXT-X-DISCONTINUITY` alone means nothing: Pluto puts one between episodes too.
 */
internal object AdBreaks {
    /** One media segment: its media sequence number, length, whether it is an ad, and its absolute URL. */
    data class Segment(val sequence: Long, val durationMs: Long, val ad: Boolean, val url: String = "")

    private val ATTRIBUTE = Regex("""([A-Za-z0-9-]+)=("[^"]*"|[^,]*)""")

    /** Pluto's ad muxer serves stitched creatives under `/v1/mp4/…` and `/mp4/v1/…`; its own bumpers are `…adbumper…` clips. */
    private val PLUTO_MUXER = Regex("""^/(?:v1/mp4|mp4/v1)/""")
    private val PLUTO_BUMPER = Regex("""ad_?bumper""", RegexOption.IGNORE_CASE)

    /** [text] with a [AdRun.TAG] line for each run of ad segments it holds; a playlist with none, or a multivariant one, comes back as it was. */
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
            if (!parsed.segments[index].ad) { index++; continue }
            val first = index
            while (index < parsed.segments.size && parsed.segments[index].ad) index++
            val segments = parsed.segments
            runs += AdRun(segments[first].sequence, segments[index - 1].sequence, parsed.plannedEndMs[first])
        }
        return runs
    }

    /** A parsed media playlist: its segments, and the planned end of a break, in ms after the segment it begins at, by segment index. */
    class Parsed(val segments: List<Segment>, val plannedEndMs: Map<Int, Long>)

    /** Reads [text], resolving segment URIs against [base]; null for a multivariant playlist, which has no segments. */
    fun parse(text: String, base: URI): Parsed? {
        if (!text.contains("#EXTINF")) return null
        var sequence = 0L
        var durationMs = 0L
        var date: Long? = null
        var cueOpen = false
        var pendingEnd: Long? = null
        val ranges = ArrayList<LongRange>()
        val segments = ArrayList<Segment>()
        val plannedEnd = HashMap<Int, Long>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> date = instantMs(line.substringAfter(':'))
                line.startsWith("#EXTINF:") -> durationMs = seconds(line.substringAfter(':').substringBefore(',')) ?: 0L
                line.startsWith("#EXT-X-CUE-OUT-CONT") -> {
                    // Each ad segment carries one; when the CUE-OUT has left the window, the first is where the break shows.
                    val (elapsed, planned) = contValues(line.substringAfter(':', ""))
                    if (!cueOpen && planned != null) pendingEnd = (planned - (elapsed ?: 0L)).coerceAtLeast(0)
                    cueOpen = true
                }
                line.startsWith("#EXT-X-CUE-OUT") -> {
                    pendingEnd = seconds(line.substringAfter(':', "").substringAfter("DURATION=").substringBefore(','))
                    cueOpen = true
                }
                line.startsWith("#EXT-X-CUE-IN") -> { cueOpen = false; pendingEnd = null }
                line.startsWith("#EXT-X-DATERANGE:") -> dateRange(line.substringAfter(':'))?.let(ranges::add)
                line.startsWith("#") -> Unit
                else -> {
                    val at = date
                    val ad = cueOpen || pluto(base, line) || (at != null && ranges.any { at in it })
                    if (pendingEnd != null) plannedEnd[segments.size] = pendingEnd
                    segments += Segment(sequence + segments.size, durationMs, ad, absolute(base, line))
                    pendingEnd = null
                    date = at?.plus(durationMs)
                    durationMs = 0L
                }
            }
        }
        return Parsed(segments, plannedEnd)
    }

    private fun absolute(base: URI, reference: String): String = try { base.resolve(reference).toString() } catch (_: IllegalArgumentException) { reference }

    /** True for a Pluto segment from its ad muxer or a bumper clip; other hosts never match, whatever their paths. */
    private fun pluto(base: URI, reference: String): Boolean {
        val uri = try { base.resolve(reference) } catch (_: IllegalArgumentException) { return false }
        val host = uri.host?.lowercase() ?: return false
        if (!host.endsWith(".plutotv.net") && !host.endsWith(".pluto.tv")) return false
        val path = uri.rawPath ?: return false
        return PLUTO_MUXER.containsMatchIn(path) || ("_Pluto_TV_OandO/" in path && PLUTO_BUMPER.containsMatchIn(path))
    }

    /** A `#EXT-X-DATERANGE` marking an SCTE-35 splice out, as the program-date span it covers; open-ended ones are left alone. */
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
        if ('/' in value && '=' !in value) return seconds(value.substringBefore('/')) to seconds(value.substringAfter('/'))
        val values = ATTRIBUTE.findAll(value).associate { it.groupValues[1].uppercase() to it.groupValues[2] }
        return seconds(values["ELAPSEDTIME"]) to seconds(values["DURATION"])
    }

    private fun seconds(value: String?): Long? = value?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).roundToLong() }

    private fun instantMs(value: String): Long? = try { Instant.parse(value.trim()).toEpochMilli() } catch (_: DateTimeParseException) { null }
}
