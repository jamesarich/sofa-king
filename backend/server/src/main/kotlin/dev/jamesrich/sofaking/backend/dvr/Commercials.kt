package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.backend.io.runFor
import dev.jamesrich.sofaking.contract.RecordingBreak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Finds a recording's commercial breaks with Comskip, a separate GPL program the server runs and never links. Comskip
 * writes a Kodi EDL of the breaks, which is kept beside the recording as `<name>.edl` (action 3, a commercial break)
 * so Kodi and other players that read one skip them too.
 */
class Commercials(
    /** The Comskip executable, `SOFA_COMSKIP`. */
    private val executable: String,
    /** A folder for Comskip's settings file and its working output. */
    private val work: Path,
    private val timeout: Duration = 4.hours,
) {
    /** The breaks in [file], empty when Comskip found none; throws when Comskip cannot run or fails. */
    suspend fun detect(file: Path): List<RecordingBreak> = withContext(Dispatchers.IO) {
        val output = work.resolve("comskip-${UUID.randomUUID()}")
        Files.createDirectories(output)
        val ini = work.resolve("comskip.ini")
        if (!Files.exists(ini)) Files.writeString(ini, INI)
        try {
            // A timeout is a failure of this scan, not a cancellation, so the scan loop goes on to the next.
            val exit = ProcessBuilder(executable, "--ini=$ini", "--output=$output", "--quiet", file.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).runFor(timeout)
                ?: throw IllegalStateException("comskip ran past $timeout")
            // Comskip exits 0 when it found breaks and 1 when it found none; anything else is a failure.
            if (exit != 0 && exit != 1) throw IllegalStateException("comskip exited $exit")
            val edl = output.resolve(file.fileName.toString().substringBeforeLast('.') + ".edl")
            if (!Files.exists(edl)) return@withContext emptyList()
            val breaks = parseEdl(Files.readString(edl))
            if (breaks.isNotEmpty()) Files.move(edl, sidecar(file), StandardCopyOption.REPLACE_EXISTING)
            breaks
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    companion object {
        /** Black frames, logo, scene changes, aspect ratio and silence; an EDL with action 3 and no other output. */
        private val INI = """
            detect_method=111
            output_default=0
            output_edl=1
            edl_skip_field=3
        """.trimIndent() + "\n"

        /** The EDL beside [recording]: the same name with `.edl`. */
        fun sidecar(recording: Path): Path =
            recording.resolveSibling(recording.fileName.toString().substringBeforeLast('.') + ".edl")

        /** Writes [breaks] to the EDL beside [recording], replacing one that is there; none leaves no file. */
        fun writeEdl(recording: Path, breaks: List<RecordingBreak>) {
            if (breaks.isEmpty()) {
                Files.deleteIfExists(sidecar(recording))
                return
            }
            val lines = breaks.joinToString("") {
                val start = it.startMs.toDouble() / MS_PER_SECOND
                val end = it.endMs.toDouble() / MS_PER_SECOND
                String.format(Locale.ROOT, "%.2f\t%.2f\t3\n", start, end)
            }
            Files.writeString(sidecar(recording), lines)
        }

        /** The commercial breaks in a Kodi EDL: `start end action` in seconds, action 3 for a commercial break. */
        fun parseEdl(text: String): List<RecordingBreak> = text.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 2) return@mapNotNull null
            val start = fields[0].toDoubleOrNull() ?: return@mapNotNull null
            val end = fields[1].toDoubleOrNull() ?: return@mapNotNull null
            if (fields.getOrNull(2)?.let { it != "3" } == true || end <= start) return@mapNotNull null
            RecordingBreak((start * MS_PER_SECOND).toLong(), (end * MS_PER_SECOND).toLong())
        }.sortedBy { it.startMs }.toList()
    }
}
