package dev.jamesrich.sofaking.ui.player

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.callback.DefaultAudioCallbackAdapter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private val log = Logger.withTag("SofaKing")

/** What the player the viewer hears is doing: the stream it holds, whether it plays, and how far in it is. */
internal class Heard(val url: String?, val playing: Boolean, val positionMs: Long)

/** What the second player, the one that only listens, is doing. */
internal class Mirrored(val url: String?, val paused: Boolean, val positionMs: Long)

/** One command for the listening player, from comparing it with [Heard]. */
internal sealed interface MirrorStep {
    data object Stop : MirrorStep

    data class Open(val url: String, val startMs: Long) : MirrorStep

    data class Pause(val paused: Boolean) : MirrorStep

    data class Seek(val positionMs: Long) : MirrorStep

    data object Keep : MirrorStep
}

/** How far the listening player may drift from the heard one before it is moved, in milliseconds. */
internal const val MIRROR_DRIFT_MS = 700L

/** The next thing to do to keep the listening player on the heard one; one step each look. */
internal fun nextStep(heard: Heard, mirror: Mirrored): MirrorStep = when {
    heard.url == null -> if (mirror.url == null) MirrorStep.Keep else MirrorStep.Stop
    heard.url != mirror.url -> MirrorStep.Open(heard.url, heard.positionMs)
    mirror.paused == heard.playing -> MirrorStep.Pause(!heard.playing)
    heard.playing && abs(mirror.positionMs - heard.positionMs) > MIRROR_DRIFT_MS -> MirrorStep.Seek(heard.positionMs)
    else -> MirrorStep.Keep
}

/**
 * libVLC plays through the system's audio output, which cannot be tapped without taking the output over (and with it
 * surround, passthrough and the sync of video). So while a visualizer is drawing, a second libVLC player opens the
 * same stream, follows the first's position, and gives its audio to [analyzer] through a callback instead of the
 * speakers. Songs are cheap to decode twice; the first player and its sound are never touched.
 */
internal class VlcAudioTap(private val commands: VlcCommands, private val heard: () -> Heard) {
    val analyzer = AudioAnalyzer(SAMPLE_RATE).also { it.onWanted = ::wanted }

    // Only the commands thread touches these.
    private var listener: MediaPlayer? = null
    private var mirrored = Mirrored(null, paused = false, positionMs = 0L)
    private var gone = false

    private val clock = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "libvlc-audio-tap").apply { isDaemon = true }
    }
    private var ticking: ScheduledFuture<*>? = null

    private fun wanted(on: Boolean) {
        synchronized(this) {
            ticking?.cancel(false)
            ticking = if (on) {
                clock.scheduleWithFixedDelay({ commands.command(::follow) }, 0, LOOK_MS, TimeUnit.MILLISECONDS)
            } else {
                commands.command(::stopListening)
                null
            }
        }
    }

    /** On the commands thread. */
    private fun follow() {
        if (gone) return
        val player = listener ?: openListener() ?: return
        runCatchingCancellable { apply(player, nextStep(heard(), mirrored)) }.onFailure {
            log.w(it) { "The visualizer's second player failed; it stays quiet" }
        }
    }

    private fun apply(player: MediaPlayer, step: MirrorStep) {
        when (step) {
            MirrorStep.Stop -> stopListening()

            is MirrorStep.Open -> {
                player.media().play(step.url, ":start-time=${step.startMs / MS_PER_SECOND}")
                mirrored = Mirrored(step.url, paused = false, positionMs = step.startMs)
            }

            is MirrorStep.Pause -> {
                player.controls().setPause(step.paused)
                mirrored = Mirrored(mirrored.url, step.paused, mirrored.positionMs)
            }

            is MirrorStep.Seek -> player.controls().setTime(step.positionMs)

            MirrorStep.Keep -> Unit
        }
        // What the listener reports; it is read here, on the commands thread, with the rest of libVLC's calls.
        if (step !is MirrorStep.Open) mirrored = Mirrored(mirrored.url, mirrored.paused, player.status().time())
    }

    private fun openListener(): MediaPlayer? {
        val factory = commands.factory ?: return null
        val opened = runCatchingCancellable {
            factory.mediaPlayers().newMediaPlayer().also {
                it.audio().callback("S16N", SAMPLE_RATE, CHANNELS, Pump(analyzer))
            }
        }.onFailure { log.w(it) { "The visualizer's second player did not open; it stays quiet" } }.getOrNull()
        listener = opened
        gone = opened == null
        return opened
    }

    private fun stopListening() {
        listener?.controls()?.stop()
        mirrored = Mirrored(null, paused = false, positionMs = 0L)
    }

    /** On the commands thread, before libVLC is released. */
    fun release() {
        gone = true
        synchronized(this) { ticking?.cancel(false) }
        clock.shutdown()
        listener?.release()
        listener = null
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val LOOK_MS = 250L
        const val MS_PER_SECOND = 1000.0
    }
}

/** Takes the samples libVLC hands over, 16-bit stereo, as floats for the analyzer. */
private class Pump(private val analyzer: AudioAnalyzer) : DefaultAudioCallbackAdapter(BYTES_PER_FRAME) {
    private var floats = FloatArray(0)

    override fun onPlay(mediaPlayer: MediaPlayer, samples: ByteArray, sampleCount: Int, pts: Long) {
        if (floats.size < sampleCount * 2) floats = FloatArray(sampleCount * 2)
        pcm16ToFloats(samples, sampleCount * 2, floats)
        analyzer.push(floats, sampleCount, 2)
    }

    private companion object {
        const val BYTES_PER_FRAME = 4
    }
}

/** Reads [count] 16-bit little-endian samples of [bytes] into [out] as -1 to 1. */
internal fun pcm16ToFloats(bytes: ByteArray, count: Int, out: FloatArray) {
    for (i in 0 until count) {
        val low = bytes[i * 2].toInt() and 0xFF
        val high = bytes[i * 2 + 1].toInt()
        out[i] = ((high shl BYTE_BITS) or low) / PCM16_SCALE
    }
}

private const val PCM16_SCALE = 32768f
private const val BYTE_BITS = 8
