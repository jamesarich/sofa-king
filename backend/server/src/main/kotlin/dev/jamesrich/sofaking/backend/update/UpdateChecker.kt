package dev.jamesrich.sofaking.backend.update

import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.io.atomicWrite
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** The build this process runs: the commit and the product version the image was built from, `unknown` outside the image. */
data class RunningBuild(val revision: String, val version: String)

/** What the registry publishes under the image's tag: the commit and version from the index annotations, and the index digest. */
data class PublishedBuild(val revision: String?, val version: String?, val digest: String?) {
    /** Whether [running] is this build; null when neither side names a commit. */
    fun isCurrent(running: RunningBuild): Boolean? = if (revision == null || running.revision == "unknown") null else revision == running.revision
}

/**
 * What the host's updater last reported, from `update-status.json` in the data directory. The outcome is one of
 * `up-to-date`, `updated`, `rolled-back`, `skipped` or `failed`; [message] is the updater's own line.
 */
@Serializable data class UpdaterReport(
    val checkedAt: String, val image: String? = null, val running: String? = null, val candidate: String? = null,
    val outcome: String, val message: String? = null,
)

/** The update picture the portal shows: what runs, what is published, when that was checked, and what the updater did last. */
class UpdateState(
    val running: RunningBuild,
    /** The image the registry is asked about; null when the check is off. */
    val image: String?,
    val published: PublishedBuild?,
    val checkedAt: Instant?,
    /** Why the last check failed, by class and a short message, never a URL. */
    val error: String?,
    val updater: UpdaterReport?,
    /** When Update now was last pressed and the request file written; null once the updater took it. */
    val requestedAt: Instant?,
) {
    val current: Boolean? get() = published?.isCurrent(running)
}

/**
 * Keeps the server aware of the published image and in touch with the host's updater. The check asks the registry
 * for the tag's index and reads the OCI annotations the build writes; it runs as the `update` job every six hours
 * after a first delay, and on request. The host's updater owns the swap: it writes [UpdaterReport] to the data
 * directory after every run and starts when the request file this class writes appears.
 */
class UpdateChecker(
    private val http: HttpClient,
    private val clock: Clock,
    private val dataDirectory: Path,
    /** `host/name:tag`; null turns the registry check off. */
    private val image: String?,
    val running: RunningBuild,
    private val jobs: Jobs,
    private val firstCheckAfter: Duration = Duration.ofMinutes(2),
    private val every: Duration = Duration.ofHours(6),
) {
    private val log = LoggerFactory.getLogger(UpdateChecker::class.java)
    private val mutex = Mutex()
    private val published = AtomicReference<PublishedBuild?>()
    private val checkedAt = AtomicReference<Instant?>()
    private val error = AtomicReference<String?>()
    private val requests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        if (image != null) jobs.register(JOB, "Update check", every) { requests.trySend(Unit) }
    }

    /** The status file the updater writes and the request file it watches, both in the data directory. */
    val statusFile: Path get() = dataDirectory.resolve(STATUS_FILE)
    val requestFile: Path get() = dataDirectory.resolve(REQUEST_FILE)

    /** The picture now, reading the updater's files; never fetches. */
    suspend fun state(): UpdateState = withContext(Dispatchers.IO) {
        UpdateState(running, image, published.get(), checkedAt.get(), error.get(), updaterReport(), requestedAt())
    }

    /** Asks the registry now; the result is kept for [state]. A failure is recorded, logged by class, and never thrown. */
    suspend fun check(): UpdateState {
        val target = image ?: return state()
        mutex.withLock {
            jobs.record(JOB) {
                try {
                    val build = fetch(target)
                    published.set(build)
                    error.set(null)
                    checkedAt.set(clock.instant())
                    JobOutcome(true, when (build.isCurrent(running)) { true -> "up to date (${build.revision})"; false -> "update available: ${build.revision}"; null -> "published ${build.revision ?: build.digest ?: "unknown"}" })
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    // Ktor's timeout messages carry the request URL; the note, the flash and the stored state never do.
                    val why = "${e.javaClass.simpleName}: ${redactedMessage(e).take(120)}".trimEnd(':', ' ')
                    error.set(why)
                    checkedAt.set(clock.instant())
                    log.warn("update check failed class={}", e.javaClass.simpleName)
                    JobOutcome(false, why)
                }
            }
        }
        return state()
    }

    /** Writes the request file the host's updater watches. False when the data directory is not writable. */
    suspend fun requestUpdate(): Boolean = withContext(Dispatchers.IO) {
        try {
            atomicWrite(requestFile, clock.instant().toString().toByteArray(), sync = false)
            true
        } catch (e: java.io.IOException) {
            log.warn("could not write the update request class={}", e.javaClass.simpleName)
            false
        }
    }

    /** Runs the check after [firstCheckAfter], then every [every] and whenever Run now asks. */
    @OptIn(ExperimentalCoroutinesApi::class) // select { onTimeout }
    fun start(scope: CoroutineScope): Job? {
        if (image == null) return null
        return scope.launch(CoroutineName("update-check")) {
            var wait = firstCheckAfter.toMillis()
            while (isActive) {
                select<Unit> {
                    requests.onReceive { }
                    onTimeout(wait) { }
                }
                check()
                wait = every.toMillis()
            }
        }
    }

    private fun updaterReport(): UpdaterReport? {
        val file = statusFile
        if (!Files.isRegularFile(file)) return null
        return try { json.decodeFromString<UpdaterReport>(Files.readString(file)) } catch (e: Exception) {
            log.debug("update-status.json is not readable class={}", e.javaClass.simpleName)
            null
        }
    }

    private fun requestedAt(): Instant? {
        val file = requestFile
        if (!Files.isRegularFile(file)) return null
        return runCatching { Instant.parse(Files.readString(file).trim()) }.getOrNull() ?: runCatching { Files.getLastModifiedTime(file).toInstant() }.getOrNull()
    }

    /**
     * The tag's index from the registry: `GET /v2/{name}/manifests/{tag}`, answering a bearer challenge with the
     * anonymous token the challenge's realm issues. The annotations come from the index, else from the manifest, else
     * from the image config's labels.
     */
    private suspend fun fetch(image: String): PublishedBuild {
        val ref = ImageRef.parse(image)
        var response = manifest(ref, ref.tag, null)
        var token: String? = null
        if (response.status == HttpStatusCode.Unauthorized) {
            token = anonymousToken(response.headers[HttpHeaders.WWWAuthenticate] ?: throw IllegalStateException("the registry asked for credentials without a challenge"))
            response = manifest(ref, ref.tag, token)
        }
        if (!response.status.isSuccess()) throw IllegalStateException("the registry answered ${response.status.value} for the manifest")
        val digest = response.headers["Docker-Content-Digest"]
        val document = parse(response)
        document.annotations()?.let { return PublishedBuild(it[REVISION], it[VERSION], digest) }
        // A single-platform manifest keeps its facts in the config blob's labels.
        val config = document["config"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
        if (config != null) {
            val blob = http.get("https://${ref.host}/v2/${ref.name}/blobs/$config") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
            if (blob.status.isSuccess()) parse(blob)["config"]?.jsonObject?.get("Labels")?.jsonObject?.let { labels ->
                return PublishedBuild(labels[REVISION]?.jsonPrimitive?.content, labels[VERSION]?.jsonPrimitive?.content, digest)
            }
        }
        return PublishedBuild(null, null, digest)
    }

    private suspend fun manifest(ref: ImageRef, tag: String, token: String?): HttpResponse =
        http.get("https://${ref.host}/v2/${ref.name}/manifests/$tag") {
            header(HttpHeaders.Accept, MANIFEST_TYPES)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    /** The token the `Bearer realm=…,service=…,scope=…` challenge names, requested without credentials. */
    private suspend fun anonymousToken(challenge: String): String {
        val fields = CHALLENGE_FIELD.findAll(challenge).associate { it.groupValues[1] to it.groupValues[2] }
        val realm = fields["realm"] ?: throw IllegalStateException("the registry's challenge names no realm")
        require(realm.startsWith("https://")) { "the registry's token realm is not https" }
        val response = http.get(realm) {
            fields["service"]?.let { parameter("service", it) }
            fields["scope"]?.let { parameter("scope", it) }
        }
        if (!response.status.isSuccess()) throw IllegalStateException("the registry answered ${response.status.value} for the token")
        val body = parse(response)
        return (body["token"] ?: body["access_token"])?.jsonPrimitive?.content ?: throw IllegalStateException("the registry's token answer holds no token")
    }

    private suspend fun parse(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()
        require(text.length <= 1_048_576) { "the registry's answer is too large" }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.annotations(): Map<String, String>? =
        this["annotations"]?.jsonObject?.takeIf { it.isNotEmpty() }?.mapValues { it.value.jsonPrimitive.content }

    /** `host/name[:tag]`; a bare `name:tag` is Docker Hub's library namespace. */
    internal data class ImageRef(val host: String, val name: String, val tag: String) {
        companion object {
            fun parse(image: String): ImageRef {
                require('@' !in image) { "the update image must be named by tag, not digest" }
                val slash = image.indexOf('/')
                val first = if (slash < 0) "" else image.substring(0, slash)
                val (host, rest) = if (slash >= 0 && ('.' in first || ':' in first || first == "localhost")) first to image.substring(slash + 1) else "registry-1.docker.io" to image
                val colon = rest.lastIndexOf(':')
                val name = if (colon < 0) rest else rest.substring(0, colon)
                val tag = if (colon < 0) "latest" else rest.substring(colon + 1)
                require(name.isNotEmpty() && tag.isNotEmpty() && name.none { it.isWhitespace() } && tag.matches(TAG)) { "the update image must be host/name:tag" }
                return ImageRef(host, if (host == "registry-1.docker.io" && '/' !in name) "library/$name" else name, tag)
            }
        }
    }

    companion object {
        const val JOB = "update"
        const val STATUS_FILE = "update-status.json"
        const val REQUEST_FILE = "update-requested"
        const val REVISION = "org.opencontainers.image.revision"
        const val VERSION = "org.opencontainers.image.version"
        private const val MANIFEST_TYPES =
            "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"
        private val CHALLENGE_FIELD = Regex("""(\w+)="([^"]*)"""")
        private val TAG = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
