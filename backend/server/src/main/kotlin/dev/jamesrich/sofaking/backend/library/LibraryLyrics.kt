package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.hash
import dev.jamesrich.sofaking.backend.io.atomicWrite
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.library
import dev.jamesrich.sofaking.backend.store.libraryItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(LibraryLyrics::class.java)

/** How long a song LRCLIB has no lyrics for waits before it is asked again. */
private val LYRICS_MISSING_FOR: Duration = Duration.ofDays(7)

/** The largest lyrics file read, in bytes. */
private const val LYRICS_LIMIT = 256 * 1024

private val lrclibJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable internal data class LrclibLyrics(
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * [LRCLIB](https://lrclib.net)'s lyrics, which need no key: a song is found by its title, artist, album and length,
 * under a User-Agent naming the server's [version], as LRCLIB asks.
 */
internal class LrclibClient(
    private val http: HttpClient,
    version: String,
    private val base: String = "https://lrclib.net/api",
) {
    private val userAgent = sofaKingAgent(version)

    /** The song's lyrics; null when LRCLIB has none. Fails when LRCLIB cannot be asked. */
    suspend fun lyrics(title: String, artist: String, album: String, seconds: Long): LrclibLyrics? {
        val response = http.get("$base/get") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("track_name", title)
            parameter("artist_name", artist)
            parameter("album_name", album)
            parameter("duration", seconds)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status == HttpStatusCode.OK) { "LRCLIB_HTTP_${response.status.value}" }
        return lrclibJson.decodeFromString<LrclibLyrics>(response.bodyAsText())
    }
}

/**
 * A song's lyrics, as LRC text: an `.lrc` file beside the track wins, else [lrclib]'s synced lyrics, else its plain
 * ones. LRCLIB's answers are kept in [directory] (which can be deleted at any time), and a song it has no lyrics for
 * is asked again after [LYRICS_MISSING_FOR].
 */
class LibraryLyrics internal constructor(
    private val store: BackendStore,
    private val lrclib: LrclibClient?,
    private val directory: Path,
    private val clock: Clock,
) {
    private val lock = Mutex()

    /** The lyrics of the track [itemId]; null for another item, or a song with none. */
    suspend fun lyrics(itemId: String): String? {
        val track = store.libraryItem(itemId)?.takeIf { it.kind == ItemKind.TRACK && it.missingSince == null }
            ?: return null
        val library = store.library(track.libraryId) ?: return null
        return beside(Path.of(library.path), track.path ?: return null) ?: fetched(track)
    }

    /** The `.lrc` file named as the track [relative] in the library folder [root], when there is one. */
    private suspend fun beside(root: Path, relative: String): String? = withContext(Dispatchers.IO) {
        val track = Path.of(relative)
        val lrc = track.resolveSibling(track.fileName.toString().substringBeforeLast('.') + ".lrc")
        root.resolveInside(lrc.toString())?.takeIf {
            Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.size(it) <= LYRICS_LIMIT
        }?.let { Files.readString(it) }
    }

    /** LRCLIB's lyrics for [track], kept once fetched; null when it has none or cannot be asked. */
    private suspend fun fetched(track: LibraryItemRecord): String? {
        val client = lrclib ?: return null
        val key = hash("lrclib-${track.id}-${track.title}-${track.durationMs}")
        val found = directory.resolve("$key.lrc")
        val missing = directory.resolve("$key.none")
        return lock.withLock {
            withContext(Dispatchers.IO) {
                when {
                    Files.isRegularFile(found) -> return@withContext Files.readString(found)

                    Files.isRegularFile(missing) && missedAt(missing).plus(LYRICS_MISSING_FOR) > clock.instant() ->
                        return@withContext null

                    else -> Unit
                }
                val song = song(track) ?: return@withContext null
                val answer = runCatchingCancellable { client.lyrics(song.title, song.artist, song.album, song.seconds) }
                    .getOrElse {
                        log.info("lyrics lookup failed class={}", it.javaClass.simpleName)
                        return@withContext null
                    }
                val text = answer?.takeUnless { it.instrumental }
                    ?.let { it.syncedLyrics?.ifBlank { null } ?: it.plainLyrics?.ifBlank { null } }
                Files.createDirectories(directory)
                if (text == null) {
                    atomicWrite(missing, clock.instant().toString().toByteArray(), sync = false)
                } else {
                    atomicWrite(found, text.toByteArray(), sync = false)
                    Files.deleteIfExists(missing)
                }
                text
            }
        }
    }

    /** What LRCLIB finds [track] by: its title, artist, album and length; null when one is unknown. */
    private suspend fun song(track: LibraryItemRecord): Song? {
        val album = track.parentId?.let { store.libraryItem(it) } ?: return null
        val artist = track.details?.artist ?: album.parentId?.let { store.libraryItem(it) }?.title ?: return null
        val seconds = (track.durationMs ?: track.media?.durationMs ?: return null) / MILLIS_PER_SECOND
        return Song(track.title, artist, album.title, seconds)
    }

    private class Song(val title: String, val artist: String, val album: String, val seconds: Long)
}

private const val MILLIS_PER_SECOND = 1_000L

/** When LRCLIB last had no lyrics, as the [file] marking it says; long ago for one that cannot be read. */
private fun missedAt(file: Path): Instant =
    runCatchingCancellable { Instant.parse(Files.readString(file).trim()) }.getOrDefault(Instant.EPOCH)
