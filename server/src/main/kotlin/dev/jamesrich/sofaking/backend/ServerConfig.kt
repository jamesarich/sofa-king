package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.http.AddressAllowList
import java.nio.file.Path

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
    /** Client IPs or CIDR blocks; empty admits every address that reaches the port, so it is refused off loopback. */
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
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): ServerConfig {
            val host = environment["SOFA_HOST"] ?: "127.0.0.1"
            require(host.isNotBlank()) { "SOFA_HOST must not be blank" }
            val port = (environment["SOFA_PORT"] ?: "8080").toIntOrNull()
            require(port != null && port in 1..65535) { "SOFA_PORT must be between 1 and 65535" }
            val directory = environment["SOFA_DATA_DIR"] ?: "data"
            require(directory.isNotBlank()) { "SOFA_DATA_DIR must not be blank" }
            val token = environment["SOFA_API_TOKEN"]
            require(token == null || token.length >= 32) { "SOFA_API_TOKEN must contain at least 32 characters" }
            val loopbackBind = host in setOf("127.0.0.1", "localhost", "::1")
            require(loopbackBind || token != null) { "Non-loopback binding requires SOFA_API_TOKEN" }
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
            val hdhrEnabled = flag(environment, "SOFA_HDHR_ENABLED", true)
            val tuners = (environment["SOFA_HDHR_TUNERS"] ?: "2").toIntOrNull()
            require(tuners != null && tuners in 1..capacity) { "SOFA_HDHR_TUNERS must be between 1 and SOFA_MAX_SESSIONS" }
            val deviceId = environment["SOFA_HDHR_DEVICE_ID"]?.uppercase()
            require(deviceId == null || deviceId.matches(Regex("[0-9A-F]{8}"))) { "SOFA_HDHR_DEVICE_ID must be eight hex digits" }
            val friendlyName = environment["SOFA_HDHR_NAME"] ?: "Sofa King"
            require(friendlyName.isNotBlank() && friendlyName.length <= 64 && friendlyName.none { it < ' ' }) { "SOFA_HDHR_NAME must be 1 to 64 printable characters" }
            val allow = environment["SOFA_HDHR_ALLOW"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            AddressAllowList(allow)
            require(!hdhrEnabled || loopbackBind || allow.isNotEmpty()) { "Non-loopback binding with SOFA_HDHR_ENABLED requires SOFA_HDHR_ALLOW" }
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
            )
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
            return "${uri!!.scheme.lowercase()}://${uri.rawAuthority}"
        }

        private fun flag(environment: Map<String, String>, name: String, default: Boolean): Boolean {
            val value = environment[name]?.lowercase() ?: return default
            require(value in setOf("true", "false")) { "$name must be true or false" }
            return value == "true"
        }
    }
}
