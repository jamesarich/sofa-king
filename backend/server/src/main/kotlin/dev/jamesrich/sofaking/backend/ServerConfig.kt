package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.http.AddressAllowList
import dev.jamesrich.sofaking.backend.library.HardwareAccel
import dev.jamesrich.sofaking.backend.library.TmdbConfig
import dev.jamesrich.sofaking.backend.library.VideoEncoder
import dev.jamesrich.sofaking.backend.logging.LogSettings
import dev.jamesrich.sofaking.backend.update.UpdateChecker
import java.nio.file.Files
import java.nio.file.Path

/** One browser origin allowed to call the server cross-origin: [host] carries the port when the origin names one. */
data class CorsOrigin(val scheme: String, val host: String) {
    override fun toString(): String = "$scheme://$host"
}

/** Player outputs: the token the M3U, XMLTV and stream URLs carry, and how far ahead the XMLTV reaches. */
data class OutputConfig(
    val token: String? = null,
    val guideDays: Int = 3,
)

/** HDHomeRun emulation for Plex: the device Plex sees, how many streams it may open, and who may reach it. */
data class HdhrConfig(
    val enabled: Boolean = true,
    val tuners: Int = 2,
    /** Eight hex digits; null derives one from the server identity. */
    val deviceId: String? = null,
    val friendlyName: String = "Sofa King",
    /** Client IPs or CIDR blocks from `SOFA_HDHR_ALLOW`; the portal adds more at run time ([dev.jamesrich.sofaking.backend.http.HdhrAccess]). */
    val allow: List<String> = emptyList(),
    /**
     * Further listening ports, each one more device carrying its share of the lineup: by a stable hash of the entry
     * ID, or with a lineup policy by range in lineup order. Plex cannot save a channel map of several hundred channels on one device.
     */
    val extraPorts: List<Int> = emptyList(),
    /** Channels per device when the lineup policy splits devices by range. */
    val deviceSize: Int = 250,
    /** The host ports Plex reaches each device on, in device order; empty means the listening ports. */
    val publicPorts: List<Int> = emptyList(),
) {
    val devices: Int get() = 1 + extraPorts.size

    /** The port Plex reaches each device on, in device order. */
    fun publicPorts(port: Int): List<Int> = publicPorts.ifEmpty { listOf(port) + extraPorts }
}

/** Plex Live TV sync: the Plex server, its token, how often to sync, and where Plex reaches the tuners when it must add one. */
class PlexConfig(
    /** Scheme, host and port, without a trailing slash. */
    val url: String,
    val token: String,
    val syncMinutes: Int = 60,
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
    val maxSessions: Int = 4,
    val ffmpeg: String = "ffmpeg",
    val probeParallelism: Int = 6,
    val restreamOrigins: Set<String> = emptySet(),
    val observationRetentionDays: Int = 7,
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
    /** `SOFA_CORS_ORIGINS`: the browser origins that may call the API from another origin; empty sends no CORS headers. */
    val corsOrigins: List<CorsOrigin> = emptyList(),
    /** `SOFA_RECORDINGS_DIR`, where the DVR writes recordings; unset, `recordings` in the data directory. */
    val recordingsDirectory: Path = dataDirectory.resolve("recordings"),
    /** `SOFA_MEDIA_DIR`: the folder media libraries live under, read only; `media` in the data directory when unset. */
    val mediaDirectory: Path = dataDirectory.resolve("media"),
    /** `SOFA_TMDB_TOKEN` and `SOFA_TMDB_LANGUAGE`: TMDB lookups for the media libraries; null leaves them off. */
    val tmdb: TmdbConfig? = null,
    /** Library files FFmpeg converts or remuxes at once (`SOFA_MAX_TRANSCODES`); direct play is not counted. */
    val maxTranscodes: Int = 2,
    /** `SOFA_HWACCEL` and `SOFA_HWACCEL_DEVICE`: the GPU encoder library conversions use; software when unset. */
    val videoEncoder: VideoEncoder = VideoEncoder(),
    /** `SOFA_RECORDINGS_FREE_GIB`: the space the DVR keeps free on the recordings folder's disk by deleting the oldest recordings. */
    val recordingsFreeGib: Int = 10,
    /** `SOFA_RECORDINGS_KEEP_DAYS`: the days the DVR keeps a recording; 0 keeps it until space runs short. */
    val recordingsKeepDays: Int = 0,
    /** `SOFA_COMSKIP`: the Comskip executable that finds commercial breaks in recordings; null when set to `none`. */
    val comskip: String? = "comskip",
) {
    companion object {
        /** The image the host's updater installs, and the one the update check asks the registry about. */
        const val DEFAULT_UPDATE_IMAGE = "ghcr.io/jamesarich/sofa-king:main"

        /** Where earlier images kept the database; a database there and none in the data directory stops the start. */
        private val LEGACY_DATA_DIRECTORY: Path = Path.of("/data")

        /**
         * The configuration the server starts with: [fromEnvironment] after reading `NAME_FILE` secrets, then the source
         * and policy files found in the data directory when their variables are unset, and, on a non-loopback bind, the
         * API, admin and output tokens the environment leaves unset from [SecretsFile], generated on first use. [log]
         * gets one line per decision and never a token.
         */
        fun load(
            environment: Map<String, String>,
            log: (String) -> Unit,
            readFile: (Path) -> String = Files::readString,
            legacyDataDirectory: Path = LEGACY_DATA_DIRECTORY,
        ): ServerConfig {
            val (resolved, origins) = readSecretFiles(environment, readFile)
            val base = fromEnvironment(resolved, requireToken = false)
            val directory = base.dataDirectory
            val legacyDatabase = legacyDataDirectory.resolve("backend.sqlite")
            require(
                directory.toAbsolutePath().normalize() == legacyDataDirectory.toAbsolutePath().normalize() ||
                    Files.exists(directory.resolve("backend.sqlite")) || !Files.isRegularFile(legacyDatabase),
            ) {
                "$legacyDatabase exists but SOFA_DATA_DIR ($directory) holds no database. " +
                    "Move the contents of $legacyDataDirectory into $directory, or set SOFA_DATA_DIR=$legacyDataDirectory"
            }
            val found = mutableSetOf<String>()
            fun pick(name: String, set: Path?, fileName: String, what: String): Path? {
                val candidate = directory.resolve(fileName)
                return when {
                    set != null -> set.also { log("config: $what file=$it from=$name") }
                    Files.isRegularFile(candidate) -> candidate.also { found += name; log("config: $what file=$it from=SOFA_DATA_DIR, $name is unset") }
                    else -> null.also { log("config: no $what file; set $name or add $fileName to $directory") }
                }
            }
            val sources = pick("SOFA_SOURCES", base.sourcesFile, "sources.json", "sources")
            val policy = pick("SOFA_POLICY", base.policyFile, "policy.json", "lineup policy")
            var apiToken = base.apiToken
            var adminToken = base.adminToken
            var outputToken = base.outputs.token
            var secretsFile: Path? = null
            val allOrigins = origins.toMutableMap()
            val missing = buildList {
                if (apiToken == null) add("apiToken")
                if (adminToken == null) add("adminToken")
                if (outputToken == null) add("outputToken")
            }
            if (!isLoopback(base.host) && missing.isNotEmpty()) {
                val file = SecretsFile(directory.resolve(SecretsFile.NAME))
                val (values, generated) = file.resolve(missing)
                secretsFile = file.path
                values["apiToken"]?.let { token ->
                    apiToken = token
                    allOrigins["SOFA_API_TOKEN"] = SecretOrigin.GENERATED
                    if ("apiToken" in generated) log("secrets: generated SOFA_API_TOKEN and stored it in ${file.path}; reveal it in the admin portal under Settings")
                    else log("secrets: SOFA_API_TOKEN from ${file.path}")
                }
                values["adminToken"]?.let { token ->
                    adminToken = token
                    allOrigins["SOFA_ADMIN_TOKEN"] = SecretOrigin.GENERATED
                    if ("adminToken" in generated) {
                        log(
                            "secrets: generated SOFA_ADMIN_TOKEN and stored it in ${file.path}. To read it: " +
                                "docker compose exec sofa-king-backend cat /config/secrets.json (outside Docker: cat ${file.path}). " +
                                "Sign in at /admin with adminToken; Settings there reveals the API token",
                        )
                    } else log("secrets: SOFA_ADMIN_TOKEN from ${file.path}")
                }
                values["outputToken"]?.let { token ->
                    outputToken = token
                    allOrigins["SOFA_OUTPUT_TOKEN"] = SecretOrigin.GENERATED
                    if ("outputToken" in generated) log("secrets: generated SOFA_OUTPUT_TOKEN and stored it in ${file.path}; the portal's Plex page shows the player URLs with it")
                    else log("secrets: SOFA_OUTPUT_TOKEN from ${file.path}")
                }
            }
            return base.copy(
                sourcesFile = sources, policyFile = policy, apiToken = apiToken, adminToken = adminToken,
                outputs = base.outputs.copy(token = outputToken), secretOrigins = allOrigins, secretsFile = secretsFile, foundInDataDirectory = found,
            )
        }

        internal fun isLoopback(host: String) = host in setOf("127.0.0.1", "localhost", "::1")

        /** [requireToken] false leaves a non-loopback bind without `SOFA_API_TOKEN` to [load], which generates one. */
        fun fromEnvironment(environment: Map<String, String>, requireToken: Boolean = true): ServerConfig {
            val host = environment["SOFA_HOST"] ?: "127.0.0.1"
            require(host.isNotBlank()) { "SOFA_HOST must not be blank" }
            val port = (environment["SOFA_PORT"] ?: "8080").toIntOrNull()
            require(port != null && port in 1..65535) { "SOFA_PORT must be between 1 and 65535" }
            val directory = environment["SOFA_DATA_DIR"] ?: "data"
            require(directory.isNotBlank()) { "SOFA_DATA_DIR must not be blank" }
            val token = environment["SOFA_API_TOKEN"]
            require(token == null || token.length >= 32) { "SOFA_API_TOKEN must contain at least 32 characters" }
            val loopbackBind = isLoopback(host)
            require(loopbackBind || token != null || !requireToken) { "Non-loopback binding requires SOFA_API_TOKEN" }
            val capacity = (environment["SOFA_MAX_SESSIONS"] ?: "4").toIntOrNull()
            require(capacity != null && capacity in 1..16) { "SOFA_MAX_SESSIONS must be between 1 and 16" }
            val sources = environment["SOFA_SOURCES"]?.let { require(it.isNotBlank()); Path.of(it) }
            val policy = environment["SOFA_POLICY"]?.let { require(it.isNotBlank()) { "SOFA_POLICY must not be blank" }; Path.of(it) }
            val parallelism = (environment["SOFA_PROBE_PARALLELISM"] ?: "6").toIntOrNull()
            require(parallelism != null && parallelism in 1..32) { "SOFA_PROBE_PARALLELISM must be between 1 and 32" }
            val origins =
                environment["SOFA_RESTREAM_ORIGINS"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            require(origins.all { it.matches(Regex("[a-z0-9.\\-\\[\\]:]+")) }) { "SOFA_RESTREAM_ORIGINS entries must be host or host:port" }
            val retention = (environment["SOFA_OBSERVATION_RETENTION_DAYS"] ?: "7").toIntOrNull()
            require(retention != null && retention in 1..3650) { "SOFA_OBSERVATION_RETENTION_DAYS must be between 1 and 3650" }
            val adminToken = environment["SOFA_ADMIN_TOKEN"]
            require(adminToken == null || adminToken.length >= 32) { "SOFA_ADMIN_TOKEN must contain at least 32 characters" }
            val loopback = flag(environment, "SOFA_ALLOW_LOOPBACK_UPSTREAMS", false)
            val outputToken = environment["SOFA_OUTPUT_TOKEN"]
            require(outputToken == null || (outputToken.length >= 32 && outputToken.all { it.isLetterOrDigit() || it == '-' || it == '_' })) {
                "SOFA_OUTPUT_TOKEN must contain at least 32 letters, digits, hyphens or underscores"
            }
            val guideDays = (environment["SOFA_XMLTV_DAYS"] ?: "3").toIntOrNull()
            require(guideDays != null && guideDays in 1..14) { "SOFA_XMLTV_DAYS must be between 1 and 14" }
            val freeGib = (environment["SOFA_RECORDINGS_FREE_GIB"] ?: "10").toIntOrNull()
            require(freeGib != null && freeGib in 0..100_000) { "SOFA_RECORDINGS_FREE_GIB must be between 0 and 100000" }
            val keepDays = (environment["SOFA_RECORDINGS_KEEP_DAYS"] ?: "0").toIntOrNull()
            require(keepDays != null && keepDays in 0..3650) { "SOFA_RECORDINGS_KEEP_DAYS must be between 0 and 3650" }
            val comskip = (environment["SOFA_COMSKIP"] ?: "comskip").trim()
            require(comskip.isNotEmpty()) { "SOFA_COMSKIP must not be blank; set it to none to turn commercial detection off" }
            val hdhrEnabled = flag(environment, "SOFA_HDHR_ENABLED", true)
            val tuners = (environment["SOFA_HDHR_TUNERS"] ?: "2").toIntOrNull()
            require(tuners != null && tuners in 1..capacity) { "SOFA_HDHR_TUNERS must be between 1 and SOFA_MAX_SESSIONS" }
            val deviceId = environment["SOFA_HDHR_DEVICE_ID"]?.uppercase()
            require(deviceId == null || deviceId.matches(Regex("[0-9A-F]{8}"))) { "SOFA_HDHR_DEVICE_ID must be eight hex digits" }
            val friendlyName = environment["SOFA_HDHR_NAME"] ?: "Sofa King"
            require(friendlyName.isNotBlank() && friendlyName.length <= 64 && friendlyName.none { it < ' ' }) { "SOFA_HDHR_NAME must be 1 to 64 printable characters" }
            val allow = environment["SOFA_HDHR_ALLOW"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            AddressAllowList(allow)
            val extraPorts = environment["SOFA_HDHR_EXTRA_PORTS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
            require(extraPorts.all { it in 1..65535 } && extraPorts.distinct().size == extraPorts.size && port !in extraPorts && extraPorts.size <= 7) {
                "SOFA_HDHR_EXTRA_PORTS must list up to 7 distinct ports other than SOFA_PORT"
            }
            val deviceSize = (environment["SOFA_HDHR_DEVICE_SIZE"] ?: "250").toIntOrNull()
            require(deviceSize != null && deviceSize in 1..5000) { "SOFA_HDHR_DEVICE_SIZE must be between 1 and 5000" }
            val publicPorts = environment["SOFA_HDHR_PUBLIC_PORTS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
            require(publicPorts.isEmpty() || (publicPorts.size == 1 + extraPorts.size && publicPorts.all { it in 1..65535 } && publicPorts.distinct().size == publicPorts.size)) {
                "SOFA_HDHR_PUBLIC_PORTS must list one distinct port per device: SOFA_PORT's, then each of SOFA_HDHR_EXTRA_PORTS"
            }
            val plex = plexConfig(environment, hdhrEnabled)
            val recordings = environment["SOFA_RECORDINGS_DIR"]?.let { require(it.isNotBlank()) { "SOFA_RECORDINGS_DIR must not be blank" }; Path.of(it) }
            return ServerConfig(
                host,
                port,
                Path.of(directory),
                sources,
                token,
                capacity,
                environment["SOFA_FFMPEG"] ?: "ffmpeg",
                parallelism,
                origins,
                retention,
                loopback,
                adminToken,
                OutputConfig(outputToken, guideDays),
                HdhrConfig(hdhrEnabled, tuners, deviceId, friendlyName, allow, extraPorts, deviceSize, publicPorts),
                policy,
                plex,
                logging = LogSettings.from(environment),
                revision = environment["SOFA_REVISION"]?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown",
                version = environment["SOFA_VERSION"]?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown",
                updateImage = environment["SOFA_UPDATE_IMAGE"]?.trim()?.takeIf { it.isNotEmpty() }?.also { UpdateChecker.ImageRef.parse(it) } ?: if (environment["SOFA_UPDATE_IMAGE"] == null) DEFAULT_UPDATE_IMAGE else null,
                corsOrigins = corsOrigins(environment["SOFA_CORS_ORIGINS"]),
                recordingsDirectory = recordings ?: Path.of(directory).resolve("recordings"),
                mediaDirectory = environment["SOFA_MEDIA_DIR"]?.let { require(it.isNotBlank()) { "SOFA_MEDIA_DIR must not be blank" }; Path.of(it) }
                    ?: Path.of(directory).resolve("media"),
                tmdb = tmdbConfig(environment),
                maxTranscodes = (environment["SOFA_MAX_TRANSCODES"] ?: "2").toIntOrNull()?.takeIf { it in 1..8 }
                    ?: throw IllegalArgumentException("SOFA_MAX_TRANSCODES must be between 1 and 8"),
                videoEncoder = VideoEncoder(
                    HardwareAccel.parse(environment["SOFA_HWACCEL"]),
                    environment["SOFA_HWACCEL_DEVICE"]?.trim()?.ifEmpty { null } ?: VideoEncoder.DEFAULT_DEVICE,
                ),
                recordingsFreeGib = freeGib,
                recordingsKeepDays = keepDays,
                comskip = comskip.takeUnless { it == "none" },
            )
        }

        internal fun corsOrigins(value: String?): List<CorsOrigin> =
            value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { origin ->
                val uri = runCatching { java.net.URI(origin) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                require(
                    uri != null && (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
                        uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null,
                ) { "SOFA_CORS_ORIGINS takes origins like https://tv.example.com or http://host:8080, separated by commas: $origin" }
                val host = uri.host.lowercase()
                CorsOrigin(scheme, if (uri.port == -1) host else "$host:${uri.port}")
            }.distinct()

        private fun tmdbConfig(environment: Map<String, String>): TmdbConfig? {
            val language = environment["SOFA_TMDB_LANGUAGE"]?.trim()
            require(language == null || language.matches(Regex("[a-z]{2,3}(-[A-Z]{2})?"))) { "SOFA_TMDB_LANGUAGE must be a language tag such as en-US" }
            val token = environment["SOFA_TMDB_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            require(token.length >= 32 && token.none { it <= ' ' || it == '\u007f' }) { "SOFA_TMDB_TOKEN must be a TMDB API read access token or API key" }
            return TmdbConfig(token, language ?: "en-US")
        }

        private fun plexConfig(environment: Map<String, String>, hdhrEnabled: Boolean): PlexConfig? {
            val url = environment["SOFA_PLEX_URL"]
            val token = environment["SOFA_PLEX_TOKEN"]
            require((url == null) == (token == null)) { "SOFA_PLEX_URL and SOFA_PLEX_TOKEN must be set together" }
            if (url == null || token == null) return null
            require(hdhrEnabled) { "SOFA_PLEX_URL requires SOFA_HDHR_ENABLED" }
            require(token.length >= 8 && token.none { it <= ' ' || it == '\u007f' }) { "SOFA_PLEX_TOKEN must contain at least 8 printable characters without spaces" }
            val minutes = (environment["SOFA_PLEX_SYNC_MINUTES"] ?: "60").toIntOrNull()
            require(minutes != null && minutes in 5..1440) { "SOFA_PLEX_SYNC_MINUTES must be between 5 and 1440" }
            val base = environment["SOFA_PLEX_TUNER_BASE"]?.let { origin(it, "SOFA_PLEX_TUNER_BASE", withPort = false) }
            return PlexConfig(origin(url, "SOFA_PLEX_URL", withPort = true), token, minutes, base)
        }

        /** [value] as `scheme://host[:port]`, rejecting paths, queries and credentials. */
        private fun origin(value: String, name: String, withPort: Boolean): String {
            val uri = runCatching { java.net.URI(value.trim().removeSuffix("/")) }.getOrNull()
            val valid = uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && (withPort || uri.port == -1)
            require(valid) { if (withPort) "$name must be http(s)://host[:port]" else "$name must be http(s)://host without a port" }
            return "${uri.scheme.lowercase()}://${uri.rawAuthority}"
        }

        private fun flag(environment: Map<String, String>, name: String, default: Boolean): Boolean {
            val value = environment[name]?.lowercase() ?: return default
            require(value in setOf("true", "false")) { "$name must be true or false" }
            return value == "true"
        }
    }
}
