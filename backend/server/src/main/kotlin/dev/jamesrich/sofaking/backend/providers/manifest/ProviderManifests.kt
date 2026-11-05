package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** A manifest and the hash of the file it came from. */
class Loaded(val manifest: ProviderManifest, val hash: String)

/**
 * The manifests in force: the ones shipped in the jar, each overridden by `<directory>/<service>.json`, and reloaded
 * while the server runs. An invalid file keeps the previous manifest and is reported in [errors].
 */
class ProviderManifests(private val directory: Path, private val onChanged: (String) -> Unit = {}) {
    private class Seen(val modified: Long, val size: Long)

    private val state = AtomicReference<Map<String, Loaded>>(shipped)
    private val seen = HashMap<String, Seen>()

    @Volatile var errors: Map<String, String> = emptyMap()
        private set

    init {
        reload(notify = false)
    }

    fun current(service: String): Loaded? = state.get()[service]

    val services: Set<String> get() = state.get().keys

    /** Whether each service's guide is its own, by service. */
    fun guides(): Map<String, Boolean> = state.get().mapValues { it.value.manifest.hasGuide }

    /** Reads the files that changed since the last look; for the job and for tests. */
    fun reloadIfChanged(): JobOutcome = reload(notify = true)

    @Synchronized
    private fun reload(notify: Boolean): JobOutcome {
        val files = try {
            listFiles()
        } catch (e: IOException) {
            return JobOutcome(false, "unreadable (${e.javaClass.simpleName})")
        }
        val problems = errors.toMutableMap()
        val next = HashMap(state.get())
        val changed = LinkedHashSet<String>()
        for (file in files) {
            val service = file.fileName.toString().removeSuffix(".json")
            if (read(file, service, next, problems)) changed += service
        }
        val present = files.map { it.fileName.toString().removeSuffix(".json") }.toSet()
        for (service in seen.keys.toList() - present) {
            seen.remove(service)
            problems.remove(service)
            shipped[service]?.let { next[service] = it } ?: next.remove(service)
            changed += service
        }
        state.set(next)
        errors = problems
        if (notify) changed.forEach(onChanged)
        return JobOutcome(problems.isEmpty(), "${changed.size} changed, ${problems.size} invalid")
    }

    private fun listFiles(): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { files -> files.filter { it.toString().endsWith(".json") }.toList() }
    }

    /** Whether [file] replaced the manifest of [service] in [next]; a file unchanged since the last look is skipped. */
    private fun read(
        file: Path,
        service: String,
        next: MutableMap<String, Loaded>,
        problems: MutableMap<String, String>,
    ): Boolean {
        val stamp = try {
            Seen(Files.getLastModifiedTime(file).toMillis(), Files.size(file))
        } catch (e: IOException) {
            problems[service] = "unreadable (${e.javaClass.simpleName})"
            return false
        }
        val before = seen[service]
        if (before != null && before.modified == stamp.modified && before.size == stamp.size) return false
        seen[service] = stamp
        return try {
            val loaded = parse(Files.readAllBytes(file), service)
            problems.remove(service)
            (next[service]?.hash != loaded.hash).also { next[service] = loaded }
        } catch (e: IOException) {
            problems[service] = "unreadable (${e.javaClass.simpleName})"
            false
        } catch (e: IllegalArgumentException) {
            problems[service] = e.message.orEmpty().lineSequence().first().take(MESSAGE_LIMIT)
            false
        }
    }

    /** Reloads every minute and when Run now asks; the Jobs page lists it. */
    fun start(scope: CoroutineScope, jobs: Jobs) {
        val requests = Channel<Unit>(Channel.CONFLATED)
        jobs.register(JOB, "Reload provider manifests", INTERVAL) { requests.trySend(Unit) }
        scope.launch(CoroutineName(JOB)) {
            while (isActive) {
                withTimeoutOrNull(INTERVAL.toMillis()) { requests.receive() }
                jobs.record(JOB) { reloadIfChanged() }
            }
        }
    }

    companion object {
        const val JOB = "provider-manifests"
        private val INTERVAL: Duration = Duration.ofSeconds(60)
        private const val MESSAGE_LIMIT = 300

        /** The services whose manifest ships in the jar, as `providers/<service>.json`. */
        val SHIPPED_NAMES = listOf("cw", "freelivesports", "hallmark", "mansa", "vizio")

        private val json = Json { ignoreUnknownKeys = false }

        /** The manifests in the jar, parsed on first use. */
        val shipped: Map<String, Loaded> by lazy {
            SHIPPED_NAMES.associateWith { name ->
                val bytes = checkNotNull(
                    ProviderManifests::class.java.classLoader.getResourceAsStream("providers/$name.json"),
                ) {
                    "missing providers/$name.json"
                }.use { it.readBytes() }
                parse(bytes, name)
            }
        }

        /** The manifest in [bytes], which must be valid and name [service]. */
        fun parse(bytes: ByteArray, service: String): Loaded {
            val manifest = try {
                json.decodeFromString(ProviderManifest.serializer(), bytes.toString(Charsets.UTF_8))
            } catch (e: kotlinx.serialization.SerializationException) {
                throw IllegalArgumentException("not a manifest: ${e.message}", e)
            }
            val problems = manifest.validate() + listOfNotNull(
                "service ${manifest.service} does not match the file name $service".takeIf {
                    manifest.service != service
                },
            )
            require(problems.isEmpty()) { problems.joinToString("; ") }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return Loaded(manifest, hash.take(HASH_LENGTH))
        }

        private const val HASH_LENGTH = 16
    }
}
