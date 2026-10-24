package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.io.atomicWrite
import dev.jamesrich.sofaking.backend.io.runFor
import dev.jamesrich.sofaking.backend.logos.LogoCache
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger(LibraryArtwork::class.java)

/** How long taking one frame may run. */
private const val FRAME_SECONDS = 30L

/** How many locks the image fetches share. */
private const val LOCK_STRIPES = 64

/** A TMDB image path as its API returns one: a slash, then letters, digits, `_` or `-`, and an image extension. */
private val TMDB_IMAGE_PATH = Regex("""/[A-Za-z0-9_-]{1,128}\.(jpg|jpeg|png|webp)""")
private val TMDB_IMAGE_SIZE = Regex("w[0-9]{2,4}|original")

/** An image ready to serve: the file and its type. */
class Artwork(val file: Path, val contentType: String)

/**
 * The posters and backdrops behind `/artwork`: a library's own image files, read in place, and TMDB's images,
 * downloaded once into [directory] (which can be deleted at any time; images are fetched again as they are asked for).
 */
class LibraryArtwork(
    private val http: HttpClient?,
    private val directory: Path,
    private val tmdbImages: String = "https://image.tmdb.org/t/p",
    private val maxBytes: Int = 8 * 1024 * 1024,
    /** FFmpeg, which takes a frame for a file with no picture; null takes none. */
    private val ffmpeg: String? = null,
) {
    /** One lock per stripe of keys, so a key always has the same one and two fetches of it never run at once. */
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    private fun lockFor(key: String): Mutex = locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)]

    /** The image [source] names, or null when it is gone, outside its library folder, or TMDB could not supply it. */
    suspend fun image(source: ArtSource): Artwork? = when (source) {
        is ArtSource.Local -> local(source)
        is ArtSource.Tmdb -> tmdb(source)
        is ArtSource.Frame -> frame(source)
    }

    /** A JPEG of the video's frame, 640 wide, taken once and kept beside the TMDB images. */
    private suspend fun frame(source: ArtSource.Frame): Artwork? {
        val ffmpeg = ffmpeg ?: return null
        val video = source.root.resolve(source.relative).normalize()
        if (!video.startsWith(source.root.normalize())) return null
        val key = "frame-" + hash(source.toString())
        val file = directory.resolve(key)
        if (withContext(Dispatchers.IO) { Files.isRegularFile(file) }) return Artwork(file, "image/jpeg")
        return lockFor(key).withLock {
            val partial = directory.resolve("$key.part.jpg")
            try {
                withContext(Dispatchers.IO) {
                    if (Files.isRegularFile(file)) return@withContext Artwork(file, "image/jpeg")
                    if (!Files.isRegularFile(video, LinkOption.NOFOLLOW_LINKS)) return@withContext null
                    Files.createDirectories(directory)
                    val seconds = "%.3f".format(java.util.Locale.ROOT, source.atMs / 1000.0)
                    val command = listOf(
                        ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y", "-ss", seconds, "-i",
                        video.toString(),
                        "-map", "0:v:0", "-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "4", partial.toString(),
                    )
                    val made = try {
                        ProcessBuilder(
                            command,
                        ).redirectErrorStream(
                            true,
                        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).runFor(FRAME_SECONDS.seconds) == 0
                    } catch (_: java.io.IOException) {
                        false
                    }
                    if (!made || !Files.isRegularFile(partial) || Files.size(partial) == 0L) {
                        log.info("artwork frame failed")
                        return@withContext null
                    }
                    Files.move(
                        partial,
                        file,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    Artwork(file, "image/jpeg")
                }
            } finally {
                // A failed or canceled grab leaves no part file; a finished one has already moved it.
                withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(partial) }
            }
        }
    }

    private suspend fun local(source: ArtSource.Local): Artwork? = withContext(Dispatchers.IO) {
        val type = typeOf(source.relative) ?: return@withContext null
        val file = source.root.resolve(source.relative).normalize()
        if (!file.startsWith(source.root.normalize()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return@withContext null
        }
        Artwork(file, type)
    }

    private suspend fun tmdb(source: ArtSource.Tmdb): Artwork? {
        if (http == null || !TMDB_IMAGE_PATH.matches(source.path) || !source.size.matches(TMDB_IMAGE_SIZE)) return null
        val type = typeOf(source.path) ?: return null
        val key = hash("${source.size}${source.path}")
        val file = directory.resolve(key)
        if (withContext(Dispatchers.IO) { Files.isRegularFile(file) }) return Artwork(file, type)
        return lockFor(key).withLock {
            if (withContext(Dispatchers.IO) { Files.isRegularFile(file) }) return@withLock Artwork(file, type)
            val bytes = download("$tmdbImages/${source.size}${source.path}") ?: return@withLock null
            withContext(Dispatchers.IO) {
                Files.createDirectories(directory)
                atomicWrite(file, bytes, sync = false)
            }
            Artwork(file, LogoCache.rasterType(bytes) ?: type)
        }
    }

    private suspend fun download(url: String): ByteArray? = try {
        http!!.prepareGet(url).execute { response ->
            if (response.status.value != 200) {
                log.info("artwork fetch failed status={}", response.status.value)
                return@execute null
            }
            response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if (it > maxBytes) return@execute null }
            val bytes = response.bodyAsChannel().readBuffer(maxBytes.toLong() + 1).readByteArray()
            bytes.takeIf { it.size <= maxBytes && LogoCache.rasterType(it) != null }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.info("artwork fetch failed class={}", e.javaClass.simpleName)
        null
    }

    private fun typeOf(name: String): String? = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }
}
