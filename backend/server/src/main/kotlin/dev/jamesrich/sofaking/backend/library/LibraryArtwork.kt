package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.MIB
import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.io.atomicWrite
import dev.jamesrich.sofaking.backend.io.runFor
import dev.jamesrich.sofaking.backend.logos.LogoCache
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.upstream.Hop
import dev.jamesrich.sofaking.backend.upstream.REDIRECT_STATUSES
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.UpstreamRejectedException
import dev.jamesrich.sofaking.backend.upstream.followRedirects
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger(LibraryArtwork::class.java)

/** How long taking one frame may run. */
private const val FRAME_SECONDS = 30L

/** How many locks the image fetches share. */
private const val LOCK_STRIPES = 64

/** The largest picture fetched. */
private const val ARTWORK_LIMIT = 8 * MIB

/** A TMDB image path as its API returns one: a slash, then letters, digits, `_` or `-`, and an image extension. */
private val TMDB_IMAGE_PATH = Regex("""/[A-Za-z0-9_-]{1,128}\.(jpg|jpeg|png|webp)""")
private val TMDB_IMAGE_SIZE = Regex("w[0-9]{2,4}|original")

/** The longest file name Commons takes, in characters. */
private const val COMMONS_NAME_LIMIT = 240

/** Whether [name] is one file's name, which no path or query can be made of. */
private fun commonsName(name: String): Boolean =
    name.isNotEmpty() && name.length <= COMMONS_NAME_LIMIT && name.none { it in "/\\#?" }

private val MUSICBRAINZ_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** An image ready to serve: the file and its type. */
class Artwork(val file: Path, val contentType: String)

/**
 * The posters and backdrops behind `/artwork`: a library's own image files, read in place, and TMDB's images, the
 * Cover Art Archive's covers, Wikimedia Commons' pictures and fanart.tv's, downloaded once into [directory] (which can
 * be deleted at any time; images are fetched again as they are asked for).
 */
class LibraryArtwork(
    private val http: HttpClient?,
    private val directory: Path,
    private val tmdbImages: String = "https://image.tmdb.org/t/p",
    private val coverArtArchive: String = COVER_ART_ARCHIVE,
    private val commonsFiles: String = "https://commons.wikimedia.org/wiki",
    private val fanartAssets: String = FANART_ASSETS,
    private val maxBytes: Int = ARTWORK_LIMIT,
    /** FFmpeg, which takes a frame for a file with no picture; null takes none. */
    private val ffmpeg: String? = null,
    private val policy: UpstreamPolicy = UpstreamPolicy(),
    /** Sent with each fetch, as Wikimedia asks a client to say who it is. */
    private val userAgent: String? = null,
) {
    /** One lock per stripe of keys, so a key always has the same one and two fetches of it never run at once. */
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    private fun lockFor(key: String): Mutex = locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)]

    /** The image [source] names, or null when it is gone, outside its library folder, or TMDB could not supply it. */
    suspend fun image(source: ArtSource): Artwork? = when (source) {
        is ArtSource.Local -> local(source)

        is ArtSource.Tmdb -> tmdb(source)

        is ArtSource.Frame -> frame(source)

        is ArtSource.CoverArt -> coverArt(source)

        is ArtSource.Commons -> commons(source)

        // The fanart.tv image as fanart.tv serves it, fetched once.
        is ArtSource.Fanart -> source.path.takeIf { http != null && FANART_PATH.matches(it) }?.let {
            fetched(hash("fanart-$it"), fanartAssets + it, typeOf(it) ?: "image/jpeg")
        }
    }

    /** The Commons image, 500 across, fetched once through Commons' redirect to its thumbnail. */
    private suspend fun commons(source: ArtSource.Commons): Artwork? {
        val name = source.file
        if (http == null || !commonsName(name)) return null
        val path = URLEncoder.encode(name.replace(' ', '_'), Charsets.UTF_8).replace("+", "%20")
        val url = "$commonsFiles/Special:FilePath/$path?width=500"
        return fetched(hash("commons-500-$name"), url, typeOf(name) ?: "image/jpeg")
    }

    /** The release group's front cover, 500 across, fetched once through the Cover Art Archive's redirects. */
    private suspend fun coverArt(source: ArtSource.CoverArt): Artwork? {
        if (http == null || !MUSICBRAINZ_ID.matches(source.releaseGroupId)) return null
        val key = hash("coverart-500-${source.releaseGroupId}")
        return fetched(key, "$coverArtArchive/release-group/${source.releaseGroupId}/front-500", "image/jpeg")
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
                    // No seek for the first frame: ffmpeg finds no picture past a seek to 0 in an MP3's attached cover.
                    val seek = if (source.atMs > 0) listOf("-ss", seconds) else emptyList()
                    val command = listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y") + seek +
                        listOf(
                            "-i", video.toString(),
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
        return fetched(hash("${source.size}${source.path}"), "$tmdbImages/${source.size}${source.path}", type)
    }

    /** The image at [url], downloaded once as [key] in the directory; [type] is what its name says it is. */
    private suspend fun fetched(key: String, url: String, type: String): Artwork? {
        val file = directory.resolve(key)
        if (withContext(Dispatchers.IO) { Files.isRegularFile(file) }) return Artwork(file, type)
        return lockFor(key).withLock {
            if (withContext(Dispatchers.IO) { Files.isRegularFile(file) }) return@withLock Artwork(file, type)
            val bytes = download(url) ?: return@withLock null
            withContext(Dispatchers.IO) {
                Files.createDirectories(directory)
                atomicWrite(file, bytes, sync = false)
            }
            Artwork(file, LogoCache.rasterType(bytes) ?: type)
        }
    }

    /** The picture at [url], following the redirects the Cover Art Archive answers with, to hosts [policy] allows. */
    private suspend fun download(url: String): ByteArray? = runCatchingCancellable {
        followRedirects(URI(url), reject = { throw UpstreamRejectedException(it.message) }) { target ->
            policy.check(target)
            http!!.prepareGet(target.toString()) {
                userAgent?.let { header(HttpHeaders.UserAgent, it) }
            }.execute { response ->
                val location = response.headers[HttpHeaders.Location]
                if (response.status.value in REDIRECT_STATUSES &&
                    location != null
                ) {
                    return@execute Hop.Redirect(location)
                }
                Hop.Done(picture(response))
            }
        }
    }.getOrElse { e ->
        log.info("artwork fetch failed class={}", e.javaClass.simpleName)
        null
    }

    private suspend fun picture(response: HttpResponse): ByteArray? {
        if (response.status != HttpStatusCode.OK) {
            log.info("artwork fetch failed status={}", response.status.value)
            return null
        }
        response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if (it > maxBytes) return null }
        val bytes = response.bodyAsChannel().readBuffer(maxBytes.toLong() + 1).readByteArray()
        return bytes.takeIf { it.size <= maxBytes && LogoCache.rasterType(it) != null }
    }

    private fun typeOf(name: String): String? = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }
}
