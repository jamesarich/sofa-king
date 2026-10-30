package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.ads.BreakMatch
import dev.jamesrich.sofaking.backend.playback.AUDIO_RATE
import dev.jamesrich.sofaking.backend.playback.CHROMAPRINT_CONTEXT
import dev.jamesrich.sofaking.backend.playback.CHROMAPRINT_HOP
import dev.jamesrich.sofaking.backend.playback.CONTINUE_POINTS
import dev.jamesrich.sofaking.backend.playback.LOUDNESS_WINDOW_MS
import dev.jamesrich.sofaking.backend.playback.SHORT_SLATE_MS
import dev.jamesrich.sofaking.backend.playback.SILENCE_DB
import dev.jamesrich.sofaking.backend.playback.SLATE_MS
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.contract.RecordingBreak
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Finds the breaks live sessions learn from the stream itself in a finished recording, under `SOFA_LEARNED_BREAKS`
 * ([mode]): a silent slate of [SLATE_MS] or more, and a run of the break [library]'s ads. The library's rules are the
 * live ones: an ad counts once it has been heard on two channels or two days and never matched show audio, and none
 * counts while the canary is frozen. A match of [BreakLibrary.MIN_LEARN_POINTS] starts a break, and a shorter one of
 * [CONTINUE_POINTS] counts when it lies within [RecordingBreaks.JOIN_MS] of one that does. `on` returns the breaks;
 * `shadow` logs each as `ADDETECT` and returns none.
 */
class RecordingAds(
    private val audio: RecordingSound,
    private val library: () -> BreakLibrary?,
    private val mode: () -> LearnedBreaks,
) {
    private val log = LoggerFactory.getLogger(Dvr::class.java)

    /** Whether a recording is searched at all: the setting is not `off`. */
    val active: Boolean get() = mode() != LearnedBreaks.OFF

    /** The learned breaks in [file]; throws when its audio cannot be read. */
    suspend fun find(file: Path, title: String): List<RecordingBreak> {
        val mode = mode()
        if (mode == LearnedBreaks.OFF) return emptyList()
        val levels = audio.levels(file) ?: throw IllegalStateException("no audio to read")
        val silences = silences(levels)
        val slates = silences.filter { it.endMs - it.startMs >= SLATE_MS }
        val library = library()
        val ads = if (library == null || library.frozen()) {
            emptyList()
        } else {
            matched(library, audio.points(file) ?: throw IllegalStateException("no audio to fingerprint"))
        }
        val breaks = RecordingBreaks.join(slates + ads, RecordingBreaks.JOIN_MS)
        silences.forEach { log("slate", it, it in slates, title, mode) }
        ads.forEach { log("library", it, true, title, mode) }
        return if (mode == LearnedBreaks.ON) breaks else emptyList()
    }

    private fun log(rule: String, span: RecordingBreak, long: Boolean, title: String, mode: LearnedBreaks) {
        val name = if (rule == "slate" && !long) "slate-short" else rule
        log.info(
            "ADDETECT rule={} state=ended marked={} lengthMs={} startMs={} title=\"{}\" recording=true",
            name,
            mode == LearnedBreaks.ON && long,
            span.endMs - span.startMs,
            span.startMs,
            title,
        )
    }

    /** The stretches of [levels] below [SILENCE_DB] of [SHORT_SLATE_MS] or more. */
    private fun silences(levels: FloatArray): List<RecordingBreak> {
        val found = ArrayList<RecordingBreak>()
        var from = -1
        for (index in 0..levels.size) {
            val silent = index < levels.size && levels[index] < SILENCE_DB
            if (silent && from < 0) from = index
            if (!silent && from >= 0) {
                val span = RecordingBreak(from * LOUDNESS_WINDOW_MS, index * LOUDNESS_WINDOW_MS)
                if (span.endMs - span.startMs >= SHORT_SLATE_MS) found += span
                from = -1
            }
        }
        return found
    }

    /** The spans of [points] that are the library's ads, by the rules in the class notes. */
    private fun matched(library: BreakLibrary, points: IntArray): List<RecordingBreak> {
        val accepted = chained(candidates(library, points))
        accepted.map { it.second.entry }.distinct().forEach(library::matched)
        return accepted.map { it.first }
    }

    /** Every blanking match of the library in [points], looked up a window at a time. */
    private fun candidates(library: BreakLibrary, points: IntArray): List<Pair<RecordingBreak, BreakMatch>> {
        val candidates = ArrayList<Pair<RecordingBreak, BreakMatch>>()
        var from = 0
        while (true) {
            val to = minOf(points.size, from + WINDOW)
            // Windows overlap by more than the longest ad, so one that a window cuts is whole in the next.
            val window = points.copyOfRange(from, to)
            val covered = BooleanArray(window.size)
            for (round in 1..MAX_WINDOW_MATCHES) {
                val match = library.match(window, CONTINUE_POINTS, skip = covered) ?: break
                for (i in match.queryFrom..match.queryTo) covered[i] = true
                // A blocked ad is still taken out of the window, so its audio is no part of another ad.
                if (match.entry.blanking) {
                    candidates +=
                        RecordingBreak(pointMs(from + match.queryFrom), pointEndMs(from + match.queryTo)) to match
                }
            }
            if (to == points.size) break
            from += STEP
        }
        return candidates
    }

    /** The matches that start a break, and those that continue one. */
    private fun chained(candidates: List<Pair<RecordingBreak, BreakMatch>>): List<Pair<RecordingBreak, BreakMatch>> {
        val accepted = ArrayList(candidates.filter { it.second.points >= BreakLibrary.MIN_LEARN_POINTS })
        var grew = true
        while (grew) {
            grew = false
            for (candidate in candidates) {
                if (candidate in accepted) continue
                val (span, _) = candidate
                if (accepted.any { (a, _) ->
                        span.startMs <= a.endMs + RecordingBreaks.JOIN_MS &&
                            a.startMs <= span.endMs + RecordingBreaks.JOIN_MS
                    }
                ) {
                    accepted += candidate
                    grew = true
                }
            }
        }
        return accepted
    }

    private fun pointMs(point: Int): Long = point.toLong() * CHROMAPRINT_HOP * MS_PER_S / AUDIO_RATE

    /** Where the audio [point] was made from ends: its start and the context its fingerprint reads. */
    private fun pointEndMs(point: Int): Long =
        (point.toLong() * CHROMAPRINT_HOP + CHROMAPRINT_CONTEXT) * MS_PER_S / AUDIO_RATE

    private companion object {
        /** About 8.5 minutes of points per library lookup, stepping by half: over the longest ad, 1,940 points. */
        const val WINDOW = 4096
        const val STEP = 2048

        /** The most ads one window can hold: a 4-minute break of 15-second spots has 16. */
        const val MAX_WINDOW_MATCHES = 64
        const val MS_PER_S = 1000L
    }
}
