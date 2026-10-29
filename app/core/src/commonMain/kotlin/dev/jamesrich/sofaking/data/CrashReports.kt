package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.CrashReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A crash waiting on the device: [key] names it in its [CrashStore], [json] is the report as it will be sent. */
class StoredCrash(val key: String, val json: String)

/**
 * Where the app keeps the crashes it has not sent. Writing runs in a dying process, so it is plain blocking code that
 * never needs the settings; each platform with a file system keeps files ([CrashStore.None] elsewhere).
 */
interface CrashStore {
    /** Keeps [json] under [key], which sorts by time, oldest first. */
    fun save(key: String, json: String)

    /** Every crash kept, oldest first. */
    fun pending(): List<StoredCrash>

    /** Forgets the crash kept under [key]. */
    fun remove(key: String)

    /** Keeps nothing: the platforms without a file system or an uncaught-exception hook. */
    data object None : CrashStore {
        override fun save(key: String, json: String) = Unit

        override fun pending(): List<StoredCrash> = emptyList()

        override fun remove(key: String) = Unit
    }
}

/** What a crash report says about where it happened; the platform's entry point knows each value. */
class CrashContext(val appVersion: String, val platform: String, val osVersion: String, val device: String)

/**
 * Turns an uncaught exception into the report the app sends to its own server, and keeps the newest [keep] on the
 * device. A report holds the exception class, a message scrubbed of anything address- or token-shaped (none for a
 * network failure), the stack frames, and the app, system and device model: no server address, token, channel or
 * title.
 */
class CrashRecorder(
    private val store: CrashStore,
    private val context: CrashContext,
    private val keep: Int = CrashLimits.KEPT,
    private val now: () -> Instant = Clock.System::now,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    /** Keeps the crash of [exceptionClass] with [message] and [trace] (a `stackTraceToString`); never throws. */
    fun record(exceptionClass: String, message: String?, trace: String) {
        // The process is dying: a report that cannot be kept is lost rather than hiding the crash itself.
        runCatchingCancellable {
            val at = now()
            val id = newId()
            val report = CrashScrub.report(exceptionClass, message, trace, context, id, at.toString())
            val key = at.toEpochMilliseconds().toString().padStart(KEY_DIGITS, '0') + "-" + id
            store.save(key, crashJson.encodeToString(report))
            store.pending().dropLast(keep).forEach { store.remove(it.key) }
        }
    }

    private companion object {
        const val KEY_DIGITS = 15
    }
}

/** The bounds on what a report keeps and sends. */
object CrashLimits {
    /** Crashes kept on the device; an older one is forgotten. */
    const val KEPT = 3
    const val MAX_MESSAGE = 200
    const val MAX_FRAMES = 40
    const val MAX_FRAME = 160
    const val MAX_LABEL = 60

    /** A report's JSON is cut to this many bytes by dropping frames, well under the server's 64 KiB. */
    const val MAX_BYTES = 12 * 1024
}

internal val crashJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The scrubbing and bounding of a crash report, as plain functions. */
internal object CrashScrub {
    private val url = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S*")
    private val bearer = Regex("\\bbearer\\s+\\S+", RegexOption.IGNORE_CASE)
    private val email = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")
    private val ipv4 = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b")
    private val quoted = Regex("\"[^\"]*\"")
    private val run = Regex("[A-Za-z0-9_+/=-]{16,}")
    private val hex = Regex("[0-9a-fA-F]+")
    private val whitespace = Regex("\\s+")

    /** Exception classes whose messages carry addresses and host names, so the message is dropped altogether. */
    private val silent = listOf(
        "java.net.", "javax.net.", "javax.security.", "java.nio.channels.", "okhttp3.", "okio.", "io.ktor.",
        "kotlinx.io.", "android.net.", "android.system.", "io.netty.",
    )

    private const val MIN_TOKEN_RUN = 24
    private const val MIN_HEX_RUN = 16

    /** [raw] without anything shaped like a URL, e-mail or IP address, bearer or long token, or quoted text. */
    fun text(raw: String, max: Int): String = raw
        .replace(url, "[url]")
        .replace(bearer, "[token]")
        .replace(email, "[email]")
        .replace(ipv4, "[address]")
        .replace(quoted, "\"…\"")
        .replace(run) { if (tokenShaped(it.value)) "[token]" else it.value }
        .replace("://", ":[url]")
        .replace(whitespace, " ")
        .trim()
        .take(max)

    /** A long run of hex digits, or a long run holding both letters and digits; a long name of letters is code. */
    private fun tokenShaped(run: String): Boolean = when {
        run.length >= MIN_HEX_RUN && hex.matches(run) -> true
        run.length >= MIN_TOKEN_RUN -> run.any(Char::isDigit) && run.any(Char::isLetter)
        else -> false
    }

    /** The message of [exceptionClass], scrubbed; empty for the classes that carry addresses. */
    fun message(exceptionClass: String, message: String?): String =
        if (message == null || silent.any(exceptionClass::startsWith)) "" else text(message, CrashLimits.MAX_MESSAGE)

    /**
     * The frames of a `stackTraceToString`: each `at` line, and a `Caused by` line as the cause's class alone. The
     * header line and the causes' messages are dropped, since a message can hold anything.
     */
    fun frames(trace: String): List<String> = trace.lineSequence().map { it.trim() }.mapNotNull { line ->
        when {
            line.startsWith("at ") -> text(line.removePrefix("at "), CrashLimits.MAX_FRAME)

            line.startsWith("Caused by: ") -> "Caused by: " + text(
                line.removePrefix("Caused by: ").substringBefore(':').substringBefore(' '),
                CrashLimits.MAX_FRAME,
            )

            else -> null
        }
    }.filter { it.isNotEmpty() }.take(CrashLimits.MAX_FRAMES).toList()

    /** The report for a crash, [id] and [occurredAt] as given, with every field scrubbed and the whole cut to size. */
    fun report(
        exceptionClass: String,
        message: String?,
        trace: String,
        context: CrashContext,
        id: String,
        occurredAt: String,
    ): CrashReport = fit(
        CrashReport(
            id = id,
            occurredAt = occurredAt,
            exception = text(exceptionClass, CrashLimits.MAX_FRAME),
            message = message(exceptionClass, message),
            frames = frames(trace),
            appVersion = text(context.appVersion, CrashLimits.MAX_LABEL),
            platform = text(context.platform, CrashLimits.MAX_LABEL),
            osVersion = text(context.osVersion, CrashLimits.MAX_LABEL),
            device = text(context.device, CrashLimits.MAX_LABEL),
        ),
    )

    /** [report] with trailing frames dropped until its JSON fits in [CrashLimits.MAX_BYTES]. */
    fun fit(report: CrashReport): CrashReport {
        var fitted = report
        while (fitted.frames.isNotEmpty() && size(fitted) > CrashLimits.MAX_BYTES) {
            fitted = fitted.copy(frames = fitted.frames.dropLast(maxOf(1, fitted.frames.size / DROP_FRACTION)))
        }
        return fitted
    }

    /** A quarter of the frames goes at a time, so a report far over the limit is cut in few steps. */
    private const val DROP_FRACTION = 4

    private fun size(report: CrashReport) = crashJson.encodeToString(report).encodeToByteArray().size
}
