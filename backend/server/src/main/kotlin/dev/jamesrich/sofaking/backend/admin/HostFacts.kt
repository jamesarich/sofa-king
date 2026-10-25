package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.library.VideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** How long a tool may take to say its version. */
private const val TOOL_TIMEOUT_SECONDS = 5L

private val COMSKIP_VERSION = Regex("""Comskip\s+(\d[\w.\-]*)""", RegexOption.IGNORE_CASE)

/** One folder the server keeps files in: its disk's free and total space, and what the server's files take in it. */
@Serializable class FolderReport(
    val name: String,
    val path: String,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
    val usedBytes: Long? = null,
)

/** The helper programs beside FFmpeg and the GPU devices the container sees, each in words. */
@Serializable class ToolReport(val comskip: String, val chromaprint: String, val gpu: String)

/**
 * What About shows of the host: the tools, read once a process since they change only with the image, and the
 * folders' disks, read on each visit. [exists] tells whether a device file is there, and [output] runs a command for
 * its output; null when it cannot run.
 */
internal class HostFacts(
    private val config: ServerConfig?,
    private val exists: (String) -> Boolean = { File(it).exists() },
    private val output: (List<String>) -> String? = ::commandOutput,
) {
    private val tools = AtomicReference<ToolReport?>()

    suspend fun tools(): ToolReport = tools.get() ?: withContext(Dispatchers.IO) {
        ToolReport(comskip(), chromaprint(), gpu())
    }.also(tools::set)

    /** The data, recordings and media folders; [recordingsUsed] is what the recordings take, when the DVR knows. */
    suspend fun folders(recordingsUsed: Long?): List<FolderReport> = withContext(Dispatchers.IO) {
        val config = config ?: return@withContext emptyList()
        listOf(
            folder("Data", config.dataDirectory, null),
            folder("Recordings", config.recordingsDirectory, recordingsUsed),
            folder("Media", config.mediaDirectory, null),
        )
    }

    private fun comskip(): String {
        val command = config?.comskip ?: return "off"
        val text = output(listOf(command)) ?: return "not found at $command"
        return COMSKIP_VERSION.find(text)?.groupValues?.get(1) ?: "found at $command"
    }

    /** Chromaprint comes as FFmpeg's `chromaprint` muxer, which intro detection and ad matching use. */
    private fun chromaprint(): String {
        val muxers = output(listOf(config?.ffmpeg ?: "ffmpeg", "-hide_banner", "-muxers")) ?: return "FFmpeg not found"
        return if (muxers.lineSequence().any { it.trim().split(Regex("\\s+")).getOrNull(1) == "chromaprint" }) {
            "in FFmpeg"
        } else {
            "missing from FFmpeg, so intro detection and ad matching are off"
        }
    }

    private fun gpu(): String = listOfNotNull(
        "Intel or AMD (${VideoEncoder.DEFAULT_DEVICE})".takeIf { exists(VideoEncoder.DEFAULT_DEVICE) },
        "NVIDIA (${VideoEncoder.NVIDIA_DEVICE})".takeIf { exists(VideoEncoder.NVIDIA_DEVICE) },
    ).joinToString(", ").ifEmpty { "none found" }

    private fun folder(name: String, path: Path, used: Long?): FolderReport {
        val absolute = path.toAbsolutePath()
        // A folder not made yet lands on its nearest existing parent's disk.
        val existing = generateSequence(absolute) { it.parent }.firstOrNull { Files.exists(it) }
        val store = existing?.let { runCatching { Files.getFileStore(it) }.getOrNull() }
        return FolderReport(name, absolute.toString(), store?.usableSpace, store?.totalSpace, used)
    }
}

/** What [command] prints, its errors included, once it exits within [TOOL_TIMEOUT_SECONDS]; null when it cannot run. */
private fun commandOutput(command: List<String>): String? = try {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    process.outputStream.close()
    // The lines these commands print fit the pipe's buffer, so the exit comes first and a hang never holds the page.
    if (process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.inputStream.bufferedReader().use { it.readText() }
    } else {
        process.destroyForcibly()
        null
    }
} catch (_: IOException) {
    null
}
