package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger(EncoderChecks::class.java)

/** The job that encodes a test clip through each encoder; the Jobs page and About run it on request. */
const val TRANSCODE_TEST_JOB = "transcode-test"

/** How long the transcode test's clip runs: long enough that the speed means something, short enough to wait for. */
const val TEST_CLIP_SECONDS = 5

/** How long the startup check's one-frame encode may take. */
private const val CHECK_TIMEOUT_SECONDS = 20L

/** How long the transcode test's clip may take through one encoder; software on a slow box is the slowest. */
private const val TEST_TIMEOUT_SECONDS = 120L

/** How long to wait for the last of FFmpeg's output once it exited. */
private const val READER_WAIT_MS = 1_000L

/** The most of FFmpeg's output kept as a failure's reason. */
private const val MAX_ERROR = 300

/** One FFmpeg run: whether it exited 0, how long it took, and its last line of output. */
class Attempt(val ok: Boolean, val millis: Long = 0, val output: String? = null)

/** One encoder tried: whether it encoded, how long it took, why not when it failed, and the FFmpeg line that ran. */
data class EncoderTrial(
    val accel: HardwareAccel,
    val ok: Boolean,
    val millis: Long,
    val error: String?,
    val command: String,
)

/**
 * What the server found its video encoders can do: [requested] is `SOFA_HWACCEL`, [chosen] what library conversions,
 * virtual channels and multiview use, and [trials] each encoder tried. [clipSeconds] is 0 for the startup check, which
 * encodes one frame, and the clip's length for the transcode test.
 */
data class EncoderVerdict(
    val requested: HardwareAccel,
    val chosen: HardwareAccel,
    val device: String,
    val trials: List<EncoderTrial>,
    val at: Instant,
    val clipSeconds: Int = 0,
) {
    /** `SOFA_HWACCEL` names a GPU encoder that does not work, so the server converts in software. */
    val failed: Boolean
        get() = requested != HardwareAccel.NONE && requested != HardwareAccel.AUTO && chosen != requested
}

/**
 * Checks which video encoder works at start, and on request encodes a test clip through each one there is, keeping
 * both verdicts for the About page and the Home page. [exists] tells whether a device file is there, and [attempt]
 * runs an FFmpeg command.
 */
class EncoderChecks(
    private val ffmpeg: String,
    private val clock: Clock,
    private val exists: (String) -> Boolean = { File(it).exists() },
    private val attempt: (List<String>, Long) -> Attempt = ::runFfmpeg,
) {
    private val checked = AtomicReference<EncoderVerdict?>()
    private val tested = AtomicReference<EncoderVerdict?>()
    private val testing = Mutex()

    /** The startup check's verdict; null before it ran. */
    val startup: EncoderVerdict? get() = checked.get()

    /** The newest transcode test's verdict; null until one ran. */
    val lastTest: EncoderVerdict? get() = tested.get()

    /**
     * [encoder] when it encodes a frame, else software, so a missing driver or device costs speed rather than
     * playback; software needs no check. [HardwareAccel.AUTO] tries NVENC when an NVIDIA device is there, then VA-API
     * and Quick Sync when the render node is, and takes the first that encodes.
     */
    suspend fun verify(encoder: VideoEncoder): VideoEncoder {
        val candidates = when (encoder.accel) {
            HardwareAccel.NONE -> emptyList()
            HardwareAccel.AUTO -> available(encoder.device)
            else -> listOf(encoder.accel)
        }
        val trials = mutableListOf<EncoderTrial>()
        val working = candidates.map { VideoEncoder(it, encoder.device) }.firstOrNull { candidate ->
            trial(candidate, 0).also { trials += it }.ok
        }
        val chosen = when {
            encoder.accel == HardwareAccel.NONE -> encoder
            working != null -> working
            else -> VideoEncoder(HardwareAccel.NONE, encoder.device)
        }
        checked.set(EncoderVerdict(encoder.accel, chosen.accel, encoder.device, trials, clock.instant()))
        logVerdict(encoder.accel, chosen.accel, candidates)
        return chosen
    }

    /** Encodes the test clip through software and each GPU encoder whose device is there, one after another. */
    suspend fun test(encoder: VideoEncoder): EncoderVerdict = testing.withLock {
        val trials = (listOf(HardwareAccel.NONE) + available(encoder.device)).map { accel ->
            trial(VideoEncoder(accel, encoder.device), TEST_CLIP_SECONDS)
        }
        val verdict = EncoderVerdict(
            startup?.requested ?: encoder.accel,
            encoder.accel,
            encoder.device,
            trials,
            clock.instant(),
            TEST_CLIP_SECONDS,
        )
        tested.set(verdict)
        verdict
    }

    /** Registers the transcode test on the Jobs page, run on request only; [encoder] is the one in use. */
    fun register(jobs: Jobs, scope: CoroutineScope, encoder: () -> VideoEncoder) {
        jobs.register(TRANSCODE_TEST_JOB, "Transcode test") {
            scope.launch(CoroutineName(TRANSCODE_TEST_JOB)) {
                jobs.record(TRANSCODE_TEST_JOB) {
                    val verdict = test(encoder())
                    JobOutcome(verdict.trials.any { it.ok && it.accel == verdict.chosen }, testSummary(verdict))
                }
            }
        }
    }

    /** The GPU encoders whose device is there, NVENC first. */
    private fun available(device: String): List<HardwareAccel> = buildList {
        if (exists(VideoEncoder.NVIDIA_DEVICE)) add(HardwareAccel.NVENC)
        if (exists(device)) {
            add(HardwareAccel.VAAPI)
            add(HardwareAccel.QSV)
        }
    }

    /** Encodes one black frame, or [seconds] of a moving 1080p test picture, through [encoder] into nothing. */
    private suspend fun trial(encoder: VideoEncoder, seconds: Int): EncoderTrial {
        val source = if (seconds == 0) {
            listOf("-f", "lavfi", "-i", "color=black:s=320x240:d=1", "-frames:v", "1")
        } else {
            listOf("-f", "lavfi", "-i", "testsrc2=s=1920x1080:r=30:d=$seconds")
        }
        val command = listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error") + encoder.inputArgs() +
            source + encoder.outputArgs(hdr = false, keyFrameSeconds = SEGMENT_SECONDS) + listOf("-f", "null", "-")
        val timeout = if (seconds == 0) CHECK_TIMEOUT_SECONDS else TEST_TIMEOUT_SECONDS
        val result = withContext(Dispatchers.IO) { attempt(command, timeout) }
        return EncoderTrial(
            encoder.accel,
            result.ok,
            result.millis,
            if (result.ok) null else (result.output ?: "FFmpeg failed").take(MAX_ERROR),
            command.joinToString(" "),
        )
    }

    private fun logVerdict(requested: HardwareAccel, chosen: HardwareAccel, tried: List<HardwareAccel>) {
        when {
            requested == HardwareAccel.NONE -> Unit

            requested == HardwareAccel.AUTO && chosen != HardwareAccel.NONE ->
                log.info("vod encoder ready accel={} (auto)", chosen.setting)

            requested == HardwareAccel.AUTO -> log.info(
                "vod encoder: no GPU encoder works (tried {}); converting in software",
                tried.joinToString { it.setting }.ifEmpty { "none" },
            )

            chosen == requested -> log.info("vod encoder ready accel={}", chosen.setting)

            else -> log.warn("vod encoder unavailable accel={}; converting in software", requested.setting)
        }
    }
}

/** One line for the Jobs page: each encoder's outcome, with its speed against real time. */
internal fun testSummary(verdict: EncoderVerdict): String = verdict.trials.joinToString("; ") { trial ->
    val name = encoderName(trial.accel)
    if (trial.ok) "$name ${speed(trial, verdict.clipSeconds)}" else "$name failed"
}

/** How much faster than real time [trial] encoded its clip, such as `6.2x`; empty for the one-frame check. */
fun speed(trial: EncoderTrial, clipSeconds: Int): String {
    if (clipSeconds == 0 || !trial.ok) return ""
    val seconds = trial.millis.coerceAtLeast(1) / 1000.0
    return "%.1fx".format(Locale.US, clipSeconds / seconds)
}

/** What an encoder is called on the portal. */
fun encoderName(accel: HardwareAccel): String = when (accel) {
    HardwareAccel.NONE, HardwareAccel.AUTO -> "Software (libx264)"
    HardwareAccel.VAAPI -> "VA-API"
    HardwareAccel.QSV -> "Quick Sync"
    HardwareAccel.NVENC -> "NVENC"
}

/** Runs [command] for at most [timeoutSeconds], keeping its last line of output. */
private fun runFfmpeg(command: List<String>, timeoutSeconds: Long): Attempt {
    val started = System.nanoTime()
    return try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val last = AtomicReference<String?>()
        // Read on its own thread, so a chatty FFmpeg never fills the pipe and stalls.
        val reader = Thread.ofVirtual().start {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { last.set(it.trim()) }
            }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Attempt(false, elapsed(started), "timed out after $timeoutSeconds s")
        }
        reader.join(READER_WAIT_MS)
        Attempt(process.exitValue() == 0, elapsed(started), last.get())
    } catch (e: IOException) {
        Attempt(false, elapsed(started), e.message)
    }
}

private fun elapsed(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
