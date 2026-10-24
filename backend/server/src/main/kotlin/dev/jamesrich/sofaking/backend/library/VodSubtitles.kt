package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.io.runFor
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.minutes

/** Where extracted subtitles are kept, under the conversion directory. */
private const val SUBTITLES = "subtitles"

/** How long reading one subtitle stream out of a file may take. */
private const val SUBTITLE_TIMEOUT_MINUTES = 10L

/** Subtitle extractions that run at once. */
private const val SUBTITLE_EXTRACTIONS = 3

/** The extracted subtitle files kept; the oldest go first. */
private const val SUBTITLES_KEPT = 200

/** The lock on one subtitle file, and how many requests hold or wait for it. */
private class ExtractionLock {
    val mutex = Mutex()
    var users = 0
}

/** The library items [VodSessions] plays, and their text subtitles as WebVTT, which [ffmpeg] extracts. */
class VodSubtitles internal constructor(
    private val catalog: LibraryCatalog,
    private val ffmpeg: String,
    directory: Path,
) {
    private val dir = directory.resolve(SUBTITLES)

    /**
     * A lock per subtitle file while anyone wants it, so one file is never extracted twice at once
     * and a slow one holds up no other.
     */
    private val extracting = HashMap<String, ExtractionLock>()

    /** Each extraction reads a whole file, so only a few run at once. */
    private val extractions = Semaphore(SUBTITLE_EXTRACTIONS)

    /**
     * Text subtitle track [index] of the library item [itemId] as WebVTT, in the item's time: a stream of its file,
     * or from [SIDECAR_BASE] a subtitle file beside it. FFmpeg converts it the first time, which reads the whole
     * file, and the copy under `subtitles/` serves after that until the file changes. A picture subtitle (PGS, DVD,
     * DVB) has no text, so it is refused; a player asks for it to be drawn into the picture instead.
     */
    suspend fun vtt(itemId: String, index: Int): Path {
        val (item, video) = itemFile(itemId)
        val source = sourceOf(item, video, index)
        if (source.picture) {
            throw ApiFailure(
                HttpStatusCode.UnsupportedMediaType,
                "SUBTITLE_NOT_TEXT",
                "Subtitle stream $index is pictures, not text",
            )
        }
        val file = source.file ?: video
        val stamp = withContext(Dispatchers.IO) { "${Files.getLastModifiedTime(file).toMillis()}-${Files.size(file)}" }
        val name = hash("$itemId/$index/$stamp").take(32)
        val out = dir.resolve("$name.vtt")
        return extractingOnly(name) {
            withContext(Dispatchers.IO) {
                if (!Files.isRegularFile(out)) extract(file, if (source.file == null) "0:$index" else "0:s:0", name)
                out
            }
        }
    }

    /** The library item [itemId] and its file, which must be a movie, episode or video inside its library's folder. */
    internal suspend fun itemFile(itemId: String): Pair<View, Path> {
        val snapshot = catalog.snapshot()
        val view = snapshot.views[itemId] ?: throw ApiFailure(HttpStatusCode.NotFound, "ITEM_NOT_FOUND", "No such item")
        if (view.record.path == null || view.record.kind == ItemKind.SHOW || view.record.kind == ItemKind.SEASON) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Only a movie, episode or video plays")
        }
        val file = snapshot.fileOf(view)
        if (file == null || withContext(Dispatchers.IO) { !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }) {
            throw ApiFailure(HttpStatusCode.NotFound, "ITEM_MISSING", "The file is not there; is its disk mounted?")
        }
        return view to file
    }

    /** Subtitle track [index] of [item], whose file is [video]: one of its streams, or a subtitle file beside it. */
    internal suspend fun sourceOf(item: View, video: Path, index: Int): SubtitleSource {
        val source = if (index >= SIDECAR_BASE) {
            withContext(Dispatchers.IO) { sidecarSubtitles(video) }
                .firstOrNull { it.stream.index == index }
                ?.let { SubtitleSource(it.stream, it.file) }
        } else {
            item.record.media?.subtitles?.firstOrNull { it.index == index }?.let { SubtitleSource(it, null) }
        }
        return source ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "No subtitle stream $index")
    }

    /** Converts stream [map] of [file] into `[name].vtt`, then drops the oldest files past [SUBTITLES_KEPT]. */
    private suspend fun extract(file: Path, map: String, name: String) {
        Files.createDirectories(dir)
        val partial = dir.resolve("$name.part")
        val command = listOf(
            ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y", "-i", file.toString(),
            "-map", map, "-c:s", "webvtt", "-f", "webvtt", partial.toString(),
        )
        // A client that gives up mid-conversion ends FFmpeg; what it wrote goes with it.
        try {
            if (!extractions.withPermit { runs(command, SUBTITLE_TIMEOUT_MINUTES) }) {
                throw ApiFailure(
                    HttpStatusCode.BadGateway,
                    "SUBTITLE_FAILED",
                    "The subtitles could not be read from the file",
                )
            }
            Files.move(
                partial,
                dir.resolve("$name.vtt"),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(partial)
        }
        prune()
    }

    /** Runs [block] holding the lock on subtitle file [name], which goes once nobody holds or waits for it. */
    private suspend fun <T> extractingOnly(name: String, block: suspend () -> T): T {
        val lock = synchronized(extracting) { extracting.getOrPut(name) { ExtractionLock() }.also { it.users++ } }
        try {
            return lock.mutex.withLock { block() }
        } finally {
            synchronized(extracting) { if (--lock.users == 0) extracting.remove(name) }
        }
    }

    private fun prune() {
        val files = Files.list(dir).use { paths -> paths.filter { it.fileName.toString().endsWith(".vtt") }.toList() }
        if (files.size <= SUBTITLES_KEPT) return
        files.sortedBy { Files.getLastModifiedTime(it) }
            .take(files.size - SUBTITLES_KEPT)
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private companion object {
        /** Whether [command] exits 0 within [minutes]; a client giving up on the request ends FFmpeg too. */
        suspend fun runs(command: List<String>, minutes: Long): Boolean = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .runFor(minutes.minutes) == 0
        } catch (_: IOException) {
            false
        }
    }
}
