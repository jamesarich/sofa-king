package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.playback.AUDIO_RATE
import dev.jamesrich.sofaking.backend.playback.CHROMAPRINT_CONTEXT
import dev.jamesrich.sofaking.backend.playback.CHROMAPRINT_HOP
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.contract.RecordingBreak
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecordingAdsTest {
    private class Sound(val levels: FloatArray?, val points: IntArray?) : RecordingSound {
        override suspend fun levels(file: Path) = levels

        override suspend fun points(file: Path) = points
    }

    private val file = Path.of("recording.ts")

    /** Random Chromaprint words; two runs of them agree only where they are the same run. */
    private fun words(seed: Int, count: Int) = Random(seed).let { random -> IntArray(count) { random.nextInt() } }

    private fun startMs(point: Int) = point.toLong() * CHROMAPRINT_HOP * 1000 / AUDIO_RATE

    private fun endMs(point: Int) = (point.toLong() * CHROMAPRINT_HOP + CHROMAPRINT_CONTEXT) * 1000 / AUDIO_RATE

    private val ad = words(2, 200)
    private val show = words(1, 3_000)

    /** The show's words with the first `length` words of the ad put over them at each `point`. */
    private fun recording(vararg at: Pair<Int, Int>): IntArray = show.copyOf().also { out ->
        for ((point, length) in at) ad.copyInto(out, point, 0, length)
    }

    private fun library(channels: Int = 2): BreakLibrary {
        val library = BreakLibrary(null, MutableClock())
        repeat(channels) { library.learn(ad, "channel-$it", "provider", "source") }
        return library
    }

    private fun find(
        points: IntArray?,
        library: BreakLibrary?,
        mode: LearnedBreaks = LearnedBreaks.ON,
        levels: FloatArray? = FloatArray(10) { -20f },
    ) = runBlocking { RecordingAds(Sound(levels, points), { library }, { mode }).find(file, "Show") }

    @Test fun `a learned ad in a recording is a break from where it starts to where its audio ends`() {
        val found = find(recording(1_000 to 200), library())
        assertEquals(listOf(RecordingBreak(startMs(1_000), endMs(1_199))), found)
    }

    @Test fun `ads close together are one break, and a short run of an ad beside one counts but a lone one does not`() {
        // The ad twice, 3.7 s apart, then 40 points of it after the second, and 40 points of it on its own.
        val points = recording(1_000 to 200, 1_230 to 200, 1_460 to 40, 2_500 to 40)
        val found = find(points, library())
        assertEquals(listOf(RecordingBreak(startMs(1_000), endMs(1_499))), found)
    }

    @Test fun `an ad heard on one channel and day only is no break yet`() {
        assertEquals(emptyList(), find(recording(1_000 to 200), library(channels = 1)))
    }

    @Test fun `an ad that matched show audio is blocked and is no break`() {
        val library = library()
        val entry = assertNotNull(library.match(ad, BreakLibrary.MIN_LEARN_POINTS)).entry
        library.falseHit(entry)
        assertEquals(emptyList(), find(recording(1_000 to 200), library))
    }

    @Test fun `shadow logs what it finds and keeps nothing, and off does not look`() {
        assertEquals(emptyList(), find(recording(1_000 to 200), library(), LearnedBreaks.SHADOW))
        val ads = RecordingAds(Sound(null, null), { null }, { LearnedBreaks.OFF })
        assertFalse(ads.active)
        assertEquals(emptyList(), runBlocking { ads.find(file, "Show") })
    }

    @Test fun `a silent stretch of twenty seconds is a slate, and a shorter one is not`() {
        fun loud(seconds: Int) = FloatArray(seconds * 10) { -20f }

        fun quiet(seconds: Int) = FloatArray(seconds * 10) { -120f }
        val levels = loud(30) + quiet(25) + loud(30) + quiet(12) + loud(30)
        assertEquals(listOf(RecordingBreak(30_000, 55_000)), find(null, null, levels = levels))
    }

    @Test fun `a recording whose audio cannot be read throws, so the scan can fail`() {
        val failure = runCatching { find(null, null, levels = null) }.exceptionOrNull()
        assertNotNull(failure)
    }

    /** A recording of [parts] (noise of a seed, or 0 for silence) with a sample count each, as PCM in a WAV file. */
    private fun wav(file: Path, vararg parts: Pair<Int, Long>) {
        val sources = parts.mapIndexed { index, (seed, samples) ->
            val source = if (seed ==
                0
            ) {
                "anullsrc=r=$AUDIO_RATE:cl=mono"
            } else {
                "anoisesrc=r=$AUDIO_RATE:c=pink:a=0.5:seed=$seed"
            }
            "$source,atrim=end_sample=$samples,asetpts=N/SR/TB,aformat=sample_fmts=s16:channel_layouts=mono[a$index]"
        }
        val inputs = parts.indices.joinToString("") { "[a$it]" }
        val filter = sources.joinToString(";") + ";${inputs}concat=n=${parts.size}:v=0:a=1"
        val exit = ProcessBuilder(
            "ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-filter_complex", filter,
            "-c:a", "pcm_s16le", file.toString(),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            .waitFor()
        assertEquals(0, exit, "ffmpeg makes the test audio")
    }

    private val second = AUDIO_RATE.toLong()

    @Test fun `a silent slate in real audio is found from the file`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-recording-ads-")
        try {
            val audio = dir.resolve("slate.wav")
            wav(audio, 3 to 30 * second, 0 to 25 * second, 4 to 30 * second)
            val found = RecordingAds(RecordingAudio("ffmpeg"), { null }, { LearnedBreaks.ON }).find(audio, "Show")
            val slate = found.single()
            assertTrue(slate.startMs in 29_900..30_100, "$slate")
            assertTrue(slate.endMs in 54_800..55_000, "$slate")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a learned ad in real audio is found from the file`() = runBlocking<Unit> {
        assumeTrue(hasChromaprint(), "this FFmpeg has no chromaprint muxer")
        val dir = Files.createTempDirectory("sofa-recording-ads-")
        try {
            // The ad starts on the Chromaprint grid, so its points are the ones it has alone.
            val show = CHROMAPRINT_HOP * 242L
            val length = CHROMAPRINT_HOP * 121L
            val clip = dir.resolve("ad.wav")
            val audio = dir.resolve("recording.wav")
            wav(clip, 9 to length)
            wav(audio, 3 to show, 9 to length, 4 to show)
            val sound = RecordingAudio("ffmpeg")
            val library = BreakLibrary(null, MutableClock())
            val learned = assertNotNull(sound.points(clip))
            repeat(2) { library.learn(learned, "channel-$it", "provider", "source") }
            val found = RecordingAds(sound, { library }, { LearnedBreaks.ON }).find(audio, "Show").single()
            assertTrue(found.startMs in 28_960..30_960, "$found")
            assertTrue(found.endMs in 43_000..45_940, "$found")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun hasChromaprint(): Boolean =
        ProcessBuilder("ffmpeg", "-hide_banner", "-muxers").redirectErrorStream(true)
            .start().inputStream.bufferedReader().readText().contains("chromaprint")
}
