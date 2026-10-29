package dev.jamesrich.sofaking.backend.setup

import dev.jamesrich.sofaking.backend.SavedSecret
import dev.jamesrich.sofaking.backend.SecretOrigin
import dev.jamesrich.sofaking.backend.SecretsFile
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.CandidateFilter
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyGroups
import dev.jamesrich.sofaking.backend.api.PolicyHealth
import dev.jamesrich.sofaking.backend.api.PolicyNames
import dev.jamesrich.sofaking.backend.api.PolicyNumbering
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import dev.jamesrich.sofaking.backend.catalog.readSourceDocument
import dev.jamesrich.sofaking.backend.catalog.sourceBytes
import dev.jamesrich.sofaking.backend.catalog.validatePolicy
import dev.jamesrich.sofaking.backend.catalog.validateSources
import dev.jamesrich.sofaking.backend.io.atomicWrite
import dev.jamesrich.sofaking.backend.library.ServiceFailure
import dev.jamesrich.sofaking.backend.library.TmdbConfig
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * What a setup write answers: the file it wrote, the starter policy when it wrote one too, and that the
 * server is restarting to read them.
 */
@Serializable data class SetupResult(val path: String, val restarting: Boolean, val policyPath: String? = null)

/**
 * Setup from the portal and the admin API: writes the source file and a starter lineup policy, then restarts the
 * server, because providers, guides and the policy are built from those files at start. The files go where the server
 * reads them: the paths `SOFA_SOURCES` and `SOFA_POLICY` name, else `sources.json` and `policy.json` in the data
 * directory. Every write is validated the way the start validates it, so a restart never fails on what was written.
 * [restart] ends the process after the response is sent; the container's restart policy brings it back.
 */
class SetupService(
    config: ServerConfig,
    private val restart: () -> Unit = ::exitForRestart,
    private val environment: Map<String, String> = System.getenv(),
    /**
     * Asks TMDB whether it takes a token, failing with [ServiceFailure]; the runtime passes a
     * [dev.jamesrich.sofaking.backend.library.TmdbClient] check.
     */
    private val checkTmdb: suspend (TmdbConfig) -> Unit = {},
    /** Asks fanart.tv whether it takes a key, failing with [ServiceFailure]; the runtime passes a client's check. */
    private val checkFanart: suspend (String) -> Unit = {},
    /** Asks ListenBrainz whether it takes a user token, failing with [ServiceFailure]. */
    private val checkListenBrainz: suspend (String) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(SetupService::class.java)
    private val mutex = Mutex()
    private val restarting = AtomicBoolean(false)

    val sourcesPath: Path = config.sourcesFile ?: config.dataDirectory.resolve("sources.json")
    val policyPath: Path = config.policyFile ?: config.dataDirectory.resolve("policy.json")

    /** Whether the server started with a source file; false means it runs with no providers. */
    val hasSources: Boolean = config.sourcesFile != null

    /** Whether the server started with a lineup policy. */
    val hasPolicy: Boolean = config.policyFile != null

    /** Whether a starter policy may be written: the server runs no policy and there is no policy file yet. */
    val canWriteStarterPolicy: Boolean get() = !hasPolicy && !Files.exists(policyPath)

    /** Where the portal saves the secrets: `secrets.json` in the data directory. */
    val secretsPath: Path = config.dataDirectory.resolve(SecretsFile.NAME)

    private val secretOrigins = config.secretOrigins

    /** Whether the environment sets [secret], which then cannot be saved from here. */
    fun fromEnvironment(secret: SavedSecret): Boolean =
        secretOrigins[secret.variable].let { it == SecretOrigin.ENVIRONMENT || it == SecretOrigin.FILE }

    /** Whether the server started with a value for [secret], from the environment or `secrets.json`. */
    fun isSet(secret: SavedSecret): Boolean = when (secret) {
        SavedSecret.TMDB -> hasTmdb
        SavedSecret.FANART -> hasFanart
        SavedSecret.LISTENBRAINZ -> hasListenBrainz
    }

    private val hasTmdb = config.tmdb != null
    private val hasFanart = config.fanartKey != null
    private val hasListenBrainz = config.listenBrainzToken != null

    private val tmdbLanguage = config.tmdb?.language ?: "en-US"

    /** Whether a restart has been requested and the process is about to end. */
    val isRestarting: Boolean get() = restarting.get()

    /** The source file as written, or an empty document when there is none. */
    suspend fun document(): SourceDocument = withContext(Dispatchers.IO) {
        if (Files.isRegularFile(sourcesPath)) readSourceDocument(sourcesPath) else SourceDocument()
    }

    /**
     * Replaces the source file with [document] and restarts. With [starterPolicy], also writes the starter policy for
     * its providers before the one restart, so a first setup is one save; it fails with 409 POLICY_EXISTS when a policy
     * is configured. Fails with 400 INVALID_SOURCES and the reason when the document would not load at start; nothing
     * is written then.
     */
    suspend fun replaceSources(document: SourceDocument, starterPolicy: Boolean = false): SetupResult = mutex.withLock {
        if (starterPolicy && !canWriteStarterPolicy) {
            throw ApiFailure(
                HttpStatusCode.Conflict,
                "POLICY_EXISTS",
                "A lineup policy is already configured at $policyPath",
            )
        }
        if (starterPolicy && document.providers.isEmpty()) {
            throw ApiFailure(HttpStatusCode.Conflict, "NO_SOURCES", "Add providers to build the starter lineup from")
        }
        try {
            validateSources(document, environment)
        } catch (e: kotlinx.serialization.SerializationException) {
            // First, as a SerializationException is an IllegalArgumentException; its message runs to many lines.
            throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_SOURCES",
                e.message?.lineSequence()?.firstOrNull() ?: "The source configuration is not valid",
                cause = e,
            )
        } catch (e: IllegalArgumentException) {
            throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_SOURCES",
                e.message ?: "The source configuration is not valid",
                cause = e,
            )
        }
        withContext(Dispatchers.IO) {
            Files.createDirectories(sourcesPath.toAbsolutePath().parent)
            atomicWrite(sourcesPath, sourceBytes(document))
        }
        log.info("setup: wrote the source file providers={} guides={}", document.providers.size, document.guides.size)
        if (starterPolicy) writeStarter(document.providers)
        requestRestart()
        SetupResult(sourcesPath.toString(), true, policyPath.toString().takeIf { starterPolicy })
    }

    /**
     * Writes the starter policy for the configured providers and restarts. Fails with 409 POLICY_EXISTS when the server
     * already runs a policy or the file is there, and 409 NO_SOURCES with no providers to build from.
     */
    suspend fun writeStarterPolicy(): SetupResult = mutex.withLock {
        if (!canWriteStarterPolicy) {
            throw ApiFailure(
                HttpStatusCode.Conflict,
                "POLICY_EXISTS",
                "A lineup policy is already configured at $policyPath",
            )
        }
        val providers = document().providers
        if (providers.isEmpty()) {
            throw ApiFailure(
                HttpStatusCode.Conflict,
                "NO_SOURCES",
                "Add providers to the source file first",
            )
        }
        writeStarter(providers)
        requestRestart()
        SetupResult(policyPath.toString(), true)
    }

    /**
     * Checks [value] with [secret]'s service, stores it in [secretsPath] and restarts, so the libraries use it. Fails
     * with 400 INVALID_REQUEST when it cannot be a secret, 400 `X_REJECTED` when the service refuses it, 502
     * `X_UNREACHABLE` when the service cannot be asked and 409 `X_FROM_ENVIRONMENT` when the environment sets it, `X`
     * being the secret's prefix; nothing is written then. The value never reaches a log or an error message.
     */
    suspend fun save(secret: SavedSecret, value: String): SetupResult = mutex.withLock {
        if (fromEnvironment(secret)) {
            throw ApiFailure(
                HttpStatusCode.Conflict,
                "${secret.prefix}_FROM_ENVIRONMENT",
                "${secret.variable} is set in the environment; change it there",
            )
        }
        val trimmed = value.trim()
        if (trimmed.length < ServerConfig.TOKEN_MIN_LENGTH || trimmed.any { it <= ' ' || it == '\u007f' }) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", secret.invalid)
        }
        try {
            check(secret, trimmed)
        } catch (e: ServiceFailure) {
            if (e.code == "${secret.prefix}_AUTH") {
                throw ApiFailure(
                    HttpStatusCode.BadRequest,
                    "${secret.prefix}_REJECTED",
                    "${secret.service} did not accept the ${secret.noun}",
                    cause = e,
                )
            }
            throw ApiFailure(
                HttpStatusCode.BadGateway,
                "${secret.prefix}_UNREACHABLE",
                "${secret.service} could not be asked (${e.code}); try again",
                cause = e,
            )
        }
        storeSecret(secretsPath, secret.key, trimmed)
        log.info("setup: saved the {} {} in {}", secret.service, secret.noun, secretsPath)
        requestRestart()
        SetupResult(secretsPath.toString(), true)
    }

    private suspend fun check(secret: SavedSecret, value: String) = when (secret) {
        SavedSecret.TMDB -> checkTmdb(TmdbConfig(value, tmdbLanguage))
        SavedSecret.FANART -> checkFanart(value)
        SavedSecret.LISTENBRAINZ -> checkListenBrainz(value)
    }

    private suspend fun writeStarter(providers: List<FeedConfig>) {
        val policy = StarterPolicy.generate(providers)
        val errors = validatePolicy(policy, providers.mapTo(HashSet()) { it.id })
        check(errors.isEmpty()) { "The starter policy is not valid: $errors" }
        withContext(Dispatchers.IO) {
            Files.createDirectories(policyPath.toAbsolutePath().parent)
            atomicWrite(policyPath, StarterPolicy.bytes(policy))
        }
        log.info("setup: wrote the starter policy entries={}", policy.providers.size)
    }

    private fun requestRestart() {
        if (restarting.compareAndSet(false, true)) {
            log.info("setup: restarting to read the new configuration")
            restart()
        }
    }

    companion object {
        /** How long the response has to reach the browser before the process ends. */
        const val EXIT_DELAY_MILLIS = 1500L

        /** Ends the process shortly, with status 0; the shutdown hooks stop the server and close the store first. */
        fun exitForRestart() = afterDelay(EXIT_DELAY_MILLIS) { exitProcess(0) }

        /** Runs [action] on a daemon thread once [millis] have passed, so the caller's response goes out first. */
        internal fun afterDelay(millis: Long, action: () -> Unit) {
            thread(name = "restart", isDaemon = true) {
                Thread.sleep(millis)
                action()
            }
        }
    }
}

/**
 * The starter lineup policy: the configured native services in the catalog's priority order (an antenna tuner first,
 * then everything else), the shipped group map and number blocks, and `requireVerified`, so stations come on as their
 * sources are verified. The same policy `scripts/generate-policy.py` writes, without the aliases it mines from a
 * running lineup; the server learns those as it runs.
 */
object StarterPolicy {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
    }

    /** Each block runs to the next block's start, sized for the free services' catalogs with room to grow. */
    val GROUP_BLOCKS: Map<String, Int> = linkedMapOf(
        "Local" to 1, "Local News (US)" to 10, "News" to 100, "Sports" to 300, "En Español" to 600,
        "Entertainment" to 700,
        "Drama" to 900, "Classic TV" to 1050, "Movies" to 1200, "Sci-Fi & Horror" to 1400, "Comedy" to 1500,
        "Crime" to 1600,
        "Documentary" to 1700, "Lifestyle" to 1850, "Food & Travel" to 2100, "Kids & Family" to 2200, "Anime" to 2300,
        "Music" to 2350, "Game Shows" to 2500, "Faith" to 2550, "Shopping" to 2580, "Ambiance" to 2600,
    )
    const val GROUP_SIZE = 400

    /** Stations the policy keeps off, and new stations past a full block, number from here, clear of every block. */
    const val NUMBER_FLOOR = 3000
    const val DEFAULT_GROUP = "Entertainment"

    fun generate(providers: List<FeedConfig>): LineupPolicy {
        val byService = providers.filter { it.native }.groupBy { it.service!! }
        val tuners = byService["hdhomerun"].orEmpty()
        val ordered = tuners + ServiceCatalog.order.filter { it != "hdhomerun" }.flatMap { byService[it].orEmpty() }
        val entries = ordered.flatMap { provider ->
            when (provider.service) {
                "localnow" -> listOf(
                    PolicyProvider(provider.id, include = CandidateFilter(groups = listOf("My City"))),
                    PolicyProvider(
                        provider.id,
                        include = CandidateFilter(groups = listOf("More Cities")),
                        enabled = false,
                    ),
                    PolicyProvider(provider.id),
                )

                "vizio" -> listOf(
                    PolicyProvider(provider.id, exclude = CandidateFilter(groups = listOf("Local channels"))),
                )

                else -> listOf(PolicyProvider(provider.id))
            }
        } + providers.filter { !it.native }.map { PolicyProvider(it.id) }
        val groups = DefaultGroups.load()
        return LineupPolicy(
            names = PolicyNames(version = 2),
            providers = entries,
            groups = PolicyGroups(
                map = groups.map,
                patterns = groups.patterns,
                default = DEFAULT_GROUP,
                weak = groups.weak.sorted(),
                builtIn = false,
            ),
            numbering = PolicyNumbering(
                floor = NUMBER_FLOOR,
                groups = GROUP_BLOCKS,
                groupSize = GROUP_SIZE,
                followGroup = true,
            ),
            health = PolicyHealth(requireVerified = true),
        )
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun bytes(policy: LineupPolicy): ByteArray =
        (json.encodeToString(LineupPolicy.serializer(), policy) + "\n").toByteArray()
}

/** Stores [value] as [name] in the secrets file at [path], failing with 500 SECRETS_UNWRITABLE when it cannot. */
private suspend fun storeSecret(path: Path, name: String, value: String) {
    try {
        withContext(Dispatchers.IO) { SecretsFile(path).store(name, value) }
    } catch (e: IllegalArgumentException) {
        throw ApiFailure(
            HttpStatusCode.InternalServerError,
            "SECRETS_UNWRITABLE",
            e.message ?: "$path cannot be written",
            cause = e,
        )
    }
}
