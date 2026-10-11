package dev.jamesrich.sofaking.backend.logos

import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.LogoRecord
import dev.jamesrich.sofaking.backend.store.deleteLogo
import dev.jamesrich.sofaking.backend.store.logos
import dev.jamesrich.sofaking.backend.store.saveLogo
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readBuffer
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory

/**
 * Lineup logos fetched once and served from `directory/<key>`, so the outputs point at this server instead of the
 * hosts the feeds name. The key is the SHA-256 of the source URL, so a changed URL is a new logo. A fetch goes
 * through the upstream address policy with no provider headers, must answer an image content type whose body
 * looks like one, and is capped at [maxBytes]. A failed refresh keeps the previous file; a failed first fetch is
 * retried after [retryAfter]. Logos the lineup no longer names are deleted by the next [sweep].
 */
class LogoCache(
    private val store: BackendStore,
    private val http: HttpClient,
    private val clock: Clock,
    private val directory: Path,
    private val policy: UpstreamPolicy,
    /** Every logo URL the lineup carries now, resolved the way the catalog resolves a channel's logo. */
    private val wanted: () -> List<String>,
    private val maxBytes: Int = MAX_BYTES,
    private val refreshAfter: Duration = Duration.ofDays(7),
    private val retryAfter: Duration = Duration.ofHours(1),
    parallelism: Int = 4,
) {
    private val log = LoggerFactory.getLogger(LogoCache::class.java)
    private val records = ConcurrentHashMap<String, LogoRecord>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val permits = Semaphore(parallelism)
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val wantedIndex = AtomicReference<Pair<List<String>, Map<String, String>>?>(null)

    suspend fun initialize() {
        withContext(Dispatchers.IO) { Files.createDirectories(directory) }
        for (record in store.logos()) records[record.key] = record
    }

    fun publicUrl(base: String, sourceUrl: String): String = "$base/logos/${keyOf(sourceUrl)}"

    fun record(key: String): LogoRecord? = records[key]

    /**
     * The cached record for [key], fetched first when the lineup carries its URL and nothing is cached yet.
     * A stale hit is served and queues a sweep. Null is a 404: unknown key, refused or failed source.
     */
    suspend fun lookup(key: String): LogoRecord? {
        val now = clock.instant()
        val record = records[key]
        if (record?.cached == true) {
            if (due(record, now)) requestSweep()
            return record
        }
        val source = wantedByKey()[key] ?: return null
        if (record != null && !retryDue(record, now)) return null
        return fetch(key, source, now)
    }

    /** The bytes of a cached record, or null when the file is gone. */
    suspend fun read(record: LogoRecord): ByteArray? = withContext(Dispatchers.IO) {
        try { Files.readAllBytes(directory.resolve(record.key)) } catch (_: NoSuchFileException) { null }
    }

    /** Fetches every wanted logo that is missing or due, [parallelism] at a time, and deletes the ones no longer wanted. */
    suspend fun sweep() {
        val now = clock.instant()
        val wanted = wantedByKey()
        for (key in records.keys.toList()) if (key !in wanted) evict(key)
        coroutineScope {
            for ((key, source) in wanted) {
                val record = records[key]
                if (record == null || (due(record, now) && retryDue(record, now))) launch { fetch(key, source, now) }
            }
        }
    }

    /** Asks the sweep loop to run soon; requests before it runs coalesce into one. */
    fun requestSweep() {
        trigger.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            trigger.receive()
            try { sweep() } catch (e: CancellationException) { throw e } catch (e: Exception) { log.warn("logo sweep failed class={}", e.javaClass.simpleName) }
        }
    }

    private fun due(record: LogoRecord, now: Instant): Boolean = record.fetchedAt == null || record.fetchedAt.plus(refreshAfter) <= now

    private fun retryDue(record: LogoRecord, now: Instant): Boolean = record.failedAt == null || record.failedAt.plus(retryAfter) <= now

    private fun wantedByKey(): Map<String, String> {
        val urls = wanted().distinct()
        wantedIndex.get()?.let { (last, index) -> if (last == urls) return index }
        val index = urls.associateBy(::keyOf)
        wantedIndex.set(urls to index)
        return index
    }

    /** One fetch per key at a time; a waiter that finds the key attempted at or after its [now] takes that result. */
    private suspend fun fetch(key: String, source: String, now: Instant): LogoRecord? = locks.computeIfAbsent(key) { Mutex() }.withLock {
        records[key]?.let { current ->
            val attempted = listOfNotNull(current.fetchedAt, current.failedAt).maxOrNull()
            if (attempted != null && attempted >= now) return@withLock current.takeIf { it.cached }
        }
        permits.withPermit {
            val previous = records[key]
            val at = clock.instant()
            val record = try {
                val image = download(source)
                write(key, image.bytes)
                LogoRecord(key, source, image.contentType, image.bytes.size.toLong(), sha256(image.bytes), fetchedAt = at, failedAt = null, failures = 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Logo URLs are not secrets, but like every upstream URL they stay out of the log.
                log.info("logo fetch failed key={} class={} cached={}", key, e.javaClass.simpleName, previous?.cached == true)
                previous?.copy(sourceUrl = source, failedAt = at, failures = previous.failures + 1)
                    ?: LogoRecord(key, source, null, 0, null, fetchedAt = null, failedAt = at, failures = 1)
            }
            store.saveLogo(record)
            records[key] = record
            record.takeIf { it.cached }
        }
    }

    private class Image(val bytes: ByteArray, val contentType: String)

    private suspend fun download(source: String): Image =
        http.withUpstreamResponse(source, source, emptyMap(), requestTimeoutMillis = 15_000, policy = policy) { response ->
            require(response.status.value == 200) { "Logo fetch answered ${response.status.value}" }
            val declared = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
            require(declared in IMAGE_TYPES) { "Not an image content type" }
            response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { require(it <= maxBytes) { "Logo exceeds size limit" } }
            val bytes = response.bodyAsChannel().readBuffer(maxBytes.toLong() + 1).readByteArray()
            require(bytes.size <= maxBytes) { "Logo exceeds size limit" }
            // The body decides the stored type: a PNG labeled JPEG is a PNG; markup is an SVG only when declared one.
            val type = rasterType(bytes) ?: (if (declared == SVG && looksLikeMarkup(bytes)) SVG else null) ?: throw IllegalArgumentException("Body is not an image")
            Image(bytes, type)
        }

    private suspend fun write(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val temporary = Files.createTempFile(directory, "$key.", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, directory.resolve(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private suspend fun evict(key: String) {
        records.remove(key)
        store.deleteLogo(key)
        withContext(Dispatchers.IO) { Files.deleteIfExists(directory.resolve(key)) }
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val SVG = "image/svg+xml"
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", SVG)
        val KEY = Regex("[0-9a-f]{64}")

        fun keyOf(sourceUrl: String): String = hash(sourceUrl)

        /** The raster type the file signature names, or null. */
        fun rasterType(bytes: ByteArray): String? = when {
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            bytes.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) -> "image/gif"
            bytes.size >= 12 && bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }

        /** An XML document start after an optional BOM and whitespace, which is what an SVG looks like. */
        fun looksLikeMarkup(bytes: ByteArray): Boolean {
            var start = 0
            if (bytes.startsWith(0xEF, 0xBB, 0xBF)) start = 3
            while (start < bytes.size && bytes[start].toInt().toChar().isWhitespace()) start++
            return start < bytes.size && bytes[start] == '<'.code.toByte()
        }

        private fun ByteArray.startsWith(vararg signature: Int): Boolean =
            size >= signature.size && signature.indices.all { this[it] == signature[it].toByte() }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
