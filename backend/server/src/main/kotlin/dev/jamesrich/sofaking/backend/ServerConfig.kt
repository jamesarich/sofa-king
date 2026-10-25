package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.library.HardwareAccel
import dev.jamesrich.sofaking.backend.library.PathMapping
import dev.jamesrich.sofaking.backend.library.TmdbConfig
import dev.jamesrich.sofaking.backend.library.VideoEncoder
import dev.jamesrich.sofaking.backend.library.pathMappings
import dev.jamesrich.sofaking.backend.logging.LogSettings
import dev.jamesrich.sofaking.backend.multiview.MultiviewMode
import dev.jamesrich.sofaking.backend.playback.DEFAULT_HISTORY_DAYS
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import java.nio.file.Files
import java.nio.file.Path

/** One browser origin allowed to call the server cross-origin: [host] carries the port when the origin names one. */
data class CorsOrigin(val scheme: String, val host: String) {
    override fun toString(): String = "$scheme://$host"
}

/** Player outputs: the token the M3U, XMLTV and stream URLs carry, and how far ahead the XMLTV reaches. */
data class OutputConfig(val token: String? = null, val guideDays: Int = ServerConfig.DEFAULT_GUIDE_DAYS)

/** HDHomeRun emulation for Plex: the device Plex sees, how many streams it may open, and who may reach it. */
data class HdhrConfig(
    val enabled: Boolean = true,
    val tuners: Int = ServerConfig.DEFAULT_TUNERS,
    /** Eight hex digits; null derives one from the server identity. */
    val deviceId: String? = null,
    val friendlyName: String = "Sofa King",
    /**
     * Client IPs or CIDR blocks from `SOFA_HDHR_ALLOW`; the portal adds more at run time
     * ([dev.jamesrich.sofaking.backend.http.HdhrAccess]).
     */
    val allow: List<String> = emptyList(),
    /**
     * Further listening ports, each one more device carrying its share of the lineup: by a stable hash of the entry
     * ID, or with a lineup policy by range in lineup order. Plex cannot save a channel map of several
     * hundred channels on one device.
     */
    val extraPorts: List<Int> = emptyList(),
    /** Channels per device when the lineup policy splits devices by range. */
    val deviceSize: Int = DEFAULT_DEVICE_SIZE,
    /** The host ports Plex reaches each device on, in device order; empty means the listening ports. */
    val publicPorts: List<Int> = emptyList(),
) {
    val devices: Int get() = 1 + extraPorts.size

    /** The port Plex reaches each device on, in device order. */
    fun publicPorts(port: Int): List<Int> = publicPorts.ifEmpty { listOf(port) + extraPorts }

    companion object {
        const val DEFAULT_DEVICE_SIZE = 250
    }
}

/**
 * Plex Live TV sync: the Plex server, its token, how often to sync, and where Plex reaches
 * the tuners when it must add one.
 */
class PlexConfig(
    /** Scheme, host and port, without a trailing slash. */
    val url: String,
    val token: String,
    val syncMinutes: Int = ServerConfig.DEFAULT_PLEX_SYNC_MINUTES,
    /** Scheme and host Plex reaches this server's tuners on; null leaves adding devices to an admin. */
    val tunerBase: String? = null,
) {
    override fun toString(): String = "PlexConfig(url=$url, syncMinutes=$syncMinutes, tunerBase=$tunerBase)"
}

data class ServerConfig(
    val host: String,
    val port: Int,
    val dataDirectory: Path,
    val sourcesFile: Path? = null,
    val apiToken: String? = null,
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    val ffmpeg: String = "ffmpeg",
    val probeParallelism: Int = DEFAULT_PROBE_PARALLELISM,
    val restreamOrigins: Set<String> = emptySet(),
    val observationRetentionDays: Int = DEFAULT_RETENTION_DAYS,
    val allowLoopbackUpstreams: Boolean = false,
    val adminToken: String? = null,
    val outputs: OutputConfig = OutputConfig(),
    val hdhr: HdhrConfig = HdhrConfig(),
    /** The lineup policy file; when set it is loaded and validated at start. */
    val policyFile: Path? = null,
    /** Plex sync when `SOFA_PLEX_URL` and `SOFA_PLEX_TOKEN` are set. */
    val plex: PlexConfig? = null,
    /** Where each set token came from, by variable name. */
    val secretOrigins: Map<String, SecretOrigin> = emptyMap(),
    /** The file holding the generated tokens, when any token is generated. */
    val secretsFile: Path? = null,
    /** `SOFA_SOURCES` and `SOFA_POLICY` when their file was found in the data directory instead of set. */
    val foundInDataDirectory: Set<String> = emptySet(),
    /** `SOFA_LOG_LEVEL` and `SOFA_LOG_FORMAT`, which logback reads itself before this is loaded. */
    val logging: LogSettings = LogSettings(),
    /** `SOFA_REVISION`, the commit the image was built from; `unknown` outside the image. */
    val revision: String = "unknown",
    /** `SOFA_VERSION`, the product version the image was built from; `unknown` outside the image. */
    val version: String = "unknown",
    /**
     * `SOFA_UPDATE_IMAGE`, the published image whose tag the update check compares with [revision]; null when it is
     * set empty, which turns the check off.
     */
    val updateImage: String? = DEFAULT_UPDATE_IMAGE,
    /**
     * `SOFA_CORS_ORIGINS`: the browser origins that may call the API from another origin; empty sends no CORS headers.
     */
    val corsOrigins: List<CorsOrigin> = emptyList(),
    /** `SOFA_RECORDINGS_DIR`, where the DVR writes recordings; unset, `recordings` in the data directory. */
    val recordingsDirectory: Path = dataDirectory.resolve("recordings"),
    /** `SOFA_MEDIA_DIR`: the folder media libraries live under, read only; `media` in the data directory when unset. */
    val mediaDirectory: Path = dataDirectory.resolve("media"),
    /** `SOFA_TMDB_TOKEN` and `SOFA_TMDB_LANGUAGE`: TMDB lookups for the media libraries; null leaves them off. */
    val tmdb: TmdbConfig? = null,
    /** Library files FFmpeg converts or remuxes at once (`SOFA_MAX_TRANSCODES`); direct play is not counted. */
    val maxTranscodes: Int = DEFAULT_MAX_TRANSCODES,
    /** `SOFA_HWACCEL` and `SOFA_HWACCEL_DEVICE`: the GPU encoder library conversions use; software when unset. */
    val videoEncoder: VideoEncoder = VideoEncoder(),
    /** `SOFA_MULTIVIEW`: composite multiview only with a GPU encoder (auto), in software too (on), or never (off). */
    val multiview: MultiviewMode = MultiviewMode.AUTO,
    /**
     * `SOFA_RECORDINGS_FREE_GIB`: the space the DVR keeps free on the recordings folder's disk by
     * deleting the oldest recordings.
     */
    val recordingsFreeGib: Int = DEFAULT_RECORDINGS_FREE_GIB,
    /** `SOFA_RECORDINGS_KEEP_DAYS`: the days the DVR keeps a recording; 0 keeps it until space runs short. */
    val recordingsKeepDays: Int = 0,
    /** `SOFA_LEARNED_BREAKS`: what live sessions do with breaks the server learns from the stream itself. */
    val learnedBreaks: LearnedBreaks = LearnedBreaks.SHADOW,
    /**
     * `SOFA_AD_LISTENERS`: how many channels the ad listener plays at once, at most 2 while anyone watches; 0 turns
     * it off.
     */
    val adListeners: Int = DEFAULT_AD_LISTENERS,
    /** `SOFA_COMSKIP`: the Comskip executable that finds commercial breaks in recordings; null when set to `none`. */
    val comskip: String? = "comskip",
    /** `SOFA_MDNS`: advertise the server on the LAN over mDNS, so apps find it; on by default off loopback. */
    val mdns: Boolean = false,
    /** `SOFA_LIBRARY_PATH_MAP`: how Sonarr, Radarr and the like name the media folders, when they see other paths. */
    val libraryPathMap: List<PathMapping> = emptyList(),
    /** How many days of ended playback sessions the history keeps, `SOFA_HISTORY_DAYS`. */
    val historyDays: Int = DEFAULT_HISTORY_DAYS,
    /**
     * `SOFA_QUALITY_FLOORS`: a remuxed stream also moves to the channel's next source on a black picture or too low a
     * rate, not only on a stall; off by default.
     */
    val qualityFloors: Boolean = false,
) {
    companion object {
        /** The image the host's updater installs, and the one the update check asks the registry about. */
        const val DEFAULT_UPDATE_IMAGE = "ghcr.io/jamesarich/sofa-king:main"

        // The defaults of the numeric variables, and the values each accepts.
        const val DEFAULT_PORT = 8080
        const val DEFAULT_MAX_SESSIONS = 4
        const val DEFAULT_PROBE_PARALLELISM = 6
        const val DEFAULT_RETENTION_DAYS = 7
        const val DEFAULT_GUIDE_DAYS = 3
        const val DEFAULT_RECORDINGS_FREE_GIB = 10
        const val DEFAULT_AD_LISTENERS = 6
        const val DEFAULT_TUNERS = 2
        const val DEFAULT_MAX_TRANSCODES = 2
        const val DEFAULT_PLEX_SYNC_MINUTES = 60
        internal val PORT_RANGE = 1..65_535
        internal val MAX_SESSIONS_RANGE = 1..16
        internal val PROBE_PARALLELISM_RANGE = 1..32
        internal val RETENTION_DAYS_RANGE = 1..3650
        internal val GUIDE_DAYS_RANGE = 1..14
        internal val RECORDINGS_FREE_GIB_RANGE = 0..100_000
        internal val RECORDINGS_KEEP_DAYS_RANGE = 0..3650
        internal val AD_LISTENERS_RANGE = 0..24
        internal val DEVICE_SIZE_RANGE = 1..5000
        internal val MAX_TRANSCODES_RANGE = 1..8
        internal val PLEX_SYNC_MINUTES_RANGE = 5..1440

        /** The shortest token the server accepts, and the shortest Plex token. */
        internal const val TOKEN_MIN_LENGTH = 32
        internal const val PLEX_TOKEN_MIN_LENGTH = 8

        /** The longest name Plex shows for a device, and the most devices beside the first. */
        internal const val DEVICE_NAME_LIMIT = 64
        internal const val EXTRA_PORTS_LIMIT = 7

        /** Where earlier images kept the database; a database there and none in the data directory stops the start. */
        internal val LEGACY_DATA_DIRECTORY: Path = Path.of("/data")

        /**
         * The configuration the server starts with: [fromEnvironment] after reading `NAME_FILE` secrets, then the
         * source and policy files found in the data directory when their variables are unset, and, on a non-loopback
         * bind, the API, admin and output tokens the environment leaves unset from [SecretsFile], generated on first
         * use. [log] gets one line per decision and never a token.
         */
        fun load(
            environment: Map<String, String>,
            log: (String) -> Unit,
            readFile: (Path) -> String = Files::readString,
            legacyDataDirectory: Path = LEGACY_DATA_DIRECTORY,
        ): ServerConfig {
            val (resolved, origins) = readSecretFiles(environment, readFile)
            val base = fromEnvironment(resolved, requireToken = false)
            requireNoLegacyDatabase(base.dataDirectory, legacyDataDirectory)
            val found = DataDirectoryFiles(base.dataDirectory, log)
            val sources = found.pick("SOFA_SOURCES", base.sourcesFile, "sources.json", "sources")
            val policy = found.pick("SOFA_POLICY", base.policyFile, "policy.json", "lineup policy")
            val allOrigins = origins.toMutableMap()
            val (tokens, secretsFile) =
                if (isLoopback(base.host)) emptyMap<String, String>() to null else storedTokens(base, allOrigins, log)
            val tmdb = base.tmdb ?: savedTmdb(resolved, base.dataDirectory, allOrigins, log)
            return base.copy(
                sourcesFile = sources,
                policyFile = policy,
                apiToken = tokens["apiToken"] ?: base.apiToken,
                adminToken = tokens["adminToken"] ?: base.adminToken,
                tmdb = tmdb,
                outputs = base.outputs.copy(token = tokens["outputToken"] ?: base.outputs.token),
                secretOrigins = allOrigins,
                secretsFile = secretsFile,
                foundInDataDirectory = found.found,
            )
        }

        internal fun isLoopback(host: String) = host in setOf("127.0.0.1", "localhost", "::1")

        /** [requireToken] false leaves a non-loopback bind without `SOFA_API_TOKEN` to [load], which generates one. */
        fun fromEnvironment(environment: Map<String, String>, requireToken: Boolean = true): ServerConfig {
            val env = EnvironmentReader(environment)
            val host = environment["SOFA_HOST"] ?: "127.0.0.1"
            require(host.isNotBlank()) { "SOFA_HOST must not be blank" }
            val port = env.number("SOFA_PORT", DEFAULT_PORT, PORT_RANGE)
            val directory = environment["SOFA_DATA_DIR"] ?: "data"
            require(directory.isNotBlank()) { "SOFA_DATA_DIR must not be blank" }
            val token = env.token("SOFA_API_TOKEN")
            require(isLoopback(host) || token != null || !requireToken) {
                "Non-loopback binding requires SOFA_API_TOKEN"
            }
            val capacity = env.number("SOFA_MAX_SESSIONS", DEFAULT_MAX_SESSIONS, MAX_SESSIONS_RANGE)
            val hdhrEnabled = env.flag("SOFA_HDHR_ENABLED", true)
            val data = Path.of(directory)
            return ServerConfig(
                host,
                port,
                data,
                env.path("SOFA_SOURCES"),
                token,
                capacity,
                environment["SOFA_FFMPEG"] ?: "ffmpeg",
                env.number("SOFA_PROBE_PARALLELISM", DEFAULT_PROBE_PARALLELISM, PROBE_PARALLELISM_RANGE),
                restreamOrigins(env),
                env.number("SOFA_OBSERVATION_RETENTION_DAYS", DEFAULT_RETENTION_DAYS, RETENTION_DAYS_RANGE),
                env.flag("SOFA_ALLOW_LOOPBACK_UPSTREAMS", false),
                env.token("SOFA_ADMIN_TOKEN"),
                outputConfig(env),
                hdhrConfig(env, port, capacity, hdhrEnabled),
                env.path("SOFA_POLICY"),
                plexConfig(env, hdhrEnabled),
                logging = LogSettings.from(environment),
                revision = env.text("SOFA_REVISION") ?: "unknown",
                version = env.text("SOFA_VERSION") ?: "unknown",
                updateImage = updateImage(env),
                corsOrigins = corsOrigins(environment["SOFA_CORS_ORIGINS"]),
                recordingsDirectory = env.path("SOFA_RECORDINGS_DIR") ?: data.resolve("recordings"),
                mediaDirectory = env.path("SOFA_MEDIA_DIR") ?: data.resolve("media"),
                tmdb = tmdbConfig(env),
                maxTranscodes = env.number("SOFA_MAX_TRANSCODES", DEFAULT_MAX_TRANSCODES, MAX_TRANSCODES_RANGE),
                videoEncoder = VideoEncoder(
                    HardwareAccel.parse(environment["SOFA_HWACCEL"]),
                    env.text("SOFA_HWACCEL_DEVICE") ?: VideoEncoder.DEFAULT_DEVICE,
                ),
                multiview = MultiviewMode.parse(environment["SOFA_MULTIVIEW"]),
                recordingsFreeGib =
                env.number("SOFA_RECORDINGS_FREE_GIB", DEFAULT_RECORDINGS_FREE_GIB, RECORDINGS_FREE_GIB_RANGE),
                recordingsKeepDays = env.number("SOFA_RECORDINGS_KEEP_DAYS", 0, RECORDINGS_KEEP_DAYS_RANGE),
                learnedBreaks = LearnedBreaks.of(environment["SOFA_LEARNED_BREAKS"] ?: "shadow")
                    ?: throw IllegalArgumentException("SOFA_LEARNED_BREAKS must be one of off, shadow, on"),
                adListeners = env.number("SOFA_AD_LISTENERS", DEFAULT_AD_LISTENERS, AD_LISTENERS_RANGE),
                comskip = comskip(env),
                mdns = env.flag("SOFA_MDNS", !isLoopback(host)),
                libraryPathMap = pathMappings(environment["SOFA_LIBRARY_PATH_MAP"]),
                historyDays = env.number("SOFA_HISTORY_DAYS", DEFAULT_HISTORY_DAYS, RETENTION_DAYS_RANGE),
                qualityFloors = env.flag("SOFA_QUALITY_FLOORS", false),
            )
        }

        internal fun corsOrigins(value: String?): List<CorsOrigin> =
            value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { origin ->
                val uri = runCatching { java.net.URI(origin) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                require(
                    uri != null && (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty() &&
                        uri.rawUserInfo == null &&
                        uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null,
                ) {
                    "SOFA_CORS_ORIGINS takes origins like https://tv.example.com or http://host:8080, separated by " +
                        "commas: $origin"
                }
                val host = uri.host.lowercase()
                CorsOrigin(scheme, if (uri.port == -1) host else "$host:${uri.port}")
            }.distinct()
    }
}
