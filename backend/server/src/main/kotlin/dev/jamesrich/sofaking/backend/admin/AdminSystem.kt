package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.ServerStatus
import dev.jamesrich.sofaking.backend.jobs.JobStatus
import dev.jamesrich.sofaking.backend.library.EncoderChecks
import dev.jamesrich.sofaking.backend.logging.LogEvent
import dev.jamesrich.sofaking.backend.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.html.a
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.picture
import kotlinx.html.q
import kotlinx.html.s
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** The server as the About page and the support snapshot describe it; nothing here is a secret. */
@Serializable class AboutServer(
    val revision: String,
    val version: String,
    val startedAt: String,
    val uptimeSeconds: Long,
    val schemaVersion: Int,
    val jvm: String,
    val os: String,
    val ffmpeg: String,
    val dataDirectory: String?,
    val logLevel: String,
    val logFormat: String,
    val memoryUsedMb: Long,
    val memoryMaxMb: Long,
)

/** One job's state for the support snapshot. */
@Serializable class JobReport(
    val id: String,
    val label: String,
    val everySeconds: Long? = null,
    val running: Boolean,
    val lastStartedAt: String? = null,
    val lastDurationMs: Long? = null,
    val lastOk: Boolean? = null,
    val lastNote: String? = null,
    val nextDueAt: String? = null,
)

/** What "Copy support snapshot" renders: the status JSON, the server facts, every job and what the overview lists. */
@Serializable class SupportSnapshot(
    val takenAt: String,
    val server: AboutServer,
    val status: ServerStatus,
    val jobs: List<JobReport>,
    val attention: List<String>,
)

private val snapshotJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
}

internal fun JobStatus.report() = JobReport(
    id, label, every?.seconds, running, lastStartedAt?.toString(), lastDurationMs, lastOk, lastNote,
    nextDueAt?.toString(),
)

internal fun SupportSnapshot.toJson(): String = snapshotJson.encodeToString(this)

internal class JobsView(val jobs: List<JobStatus>)

internal class EventsView(
    val events: List<LogEvent>,
    val kept: Int,
    val capacity: Int,
    val level: String,
    val q: String,
    /** Whether the server writes a log file the page can offer for download. */
    val logFile: Boolean = false,
    /** When the spell of DEBUG logging ends; null while there is none. */
    val debugUntil: java.time.Instant? = null,
    /** How long a spell of DEBUG logging lasts, in minutes; null when the server cannot start one. */
    val debugMinutes: Long? = null,
)

/**
 * The About page: the server facts, the status, the snapshot, and the update picture, with [confirm] set while Update
 * now waits for the operator's yes.
 */
internal class AboutView(
    val server: AboutServer,
    val status: ServerStatus,
    val snapshot: String,
    val update: UpdateState? = null,
    val confirm: Boolean = false,
    /** The video encoder checks; null without library playback. */
    val encoders: EncoderChecks? = null,
) {
    constructor(view: AboutView, confirm: Boolean) :
        this(view.server, view.status, view.snapshot, view.update, confirm, view.encoders)
}

/** The first line of `ffmpeg -version`, or why it could not be read; [ffmpeg] is the configured path. */
internal suspend fun ffmpegVersion(ffmpeg: String): String = withContext(Dispatchers.IO) {
    try {
        val process = ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true).start()
        // The few lines it prints fit the pipe's buffer, so the exit comes first and an FFmpeg
        // that hangs never holds the page.
        if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@withContext "not readable at $ffmpeg"
        }
        val line = process.inputStream.bufferedReader().use { it.readLine() }
        line?.takeIf { it.startsWith("ffmpeg") }?.removePrefix("ffmpeg version ")?.substringBefore(" Copyright")?.trim()
            ?: "not readable at $ffmpeg"
    } catch (_: java.io.IOException) {
        "not found at $ffmpeg"
    }
}

/** How long `ffmpeg -version` may take. */
private const val VERSION_TIMEOUT_SECONDS = 5L
