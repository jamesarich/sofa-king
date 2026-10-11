package dev.jamesrich.sofaking.backend

import java.nio.file.Path

/** Player outputs: the token the M3U, XMLTV and stream URLs carry, and how far ahead the XMLTV reaches. */
data class OutputConfig(
    val token: String? = null,
    val guideDays: Int = 3,
)

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
            require(host in setOf("127.0.0.1", "localhost", "::1") || token != null) { "Non-loopback binding requires SOFA_API_TOKEN" }
            val capacity = (environment["SOFA_MAX_SESSIONS"] ?: "4").toIntOrNull()
            require(capacity != null && capacity in 1..16) { "SOFA_MAX_SESSIONS must be between 1 and 16" }
            val sources = environment["SOFA_SOURCES"]?.let { require(it.isNotBlank()); Path.of(it) }
            val parallelism = (environment["SOFA_PROBE_PARALLELISM"] ?: "6").toIntOrNull()
            require(parallelism != null && parallelism in 1..32) { "SOFA_PROBE_PARALLELISM must be between 1 and 32" }
            val origins =
                environment["SOFA_RESTREAM_ORIGINS"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            require(origins.all { it.matches(Regex("[a-z0-9.\\-\\[\\]:]+")) }) { "SOFA_RESTREAM_ORIGINS entries must be host or host:port" }
            val retention = (environment["SOFA_OBSERVATION_RETENTION_DAYS"] ?: "7").toIntOrNull()
            require(retention != null && retention in 1..3650) { "SOFA_OBSERVATION_RETENTION_DAYS must be between 1 and 3650" }
            val adminToken = environment["SOFA_ADMIN_TOKEN"]
            require(adminToken == null || adminToken.length >= 32) { "SOFA_ADMIN_TOKEN must contain at least 32 characters" }
            val loopback = environment["SOFA_ALLOW_LOOPBACK_UPSTREAMS"]?.lowercase()
            require(loopback == null || loopback in setOf("true", "false")) { "SOFA_ALLOW_LOOPBACK_UPSTREAMS must be true or false" }
            val outputToken = environment["SOFA_OUTPUT_TOKEN"]
            require(outputToken == null || (outputToken.length >= 32 && outputToken.all { it.isLetterOrDigit() || it == '-' || it == '_' })) {
                "SOFA_OUTPUT_TOKEN must contain at least 32 letters, digits, hyphens or underscores"
            }
            val guideDays = (environment["SOFA_XMLTV_DAYS"] ?: "3").toIntOrNull()
            require(guideDays != null && guideDays in 1..14) { "SOFA_XMLTV_DAYS must be between 1 and 14" }
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
                loopback == "true",
                adminToken,
                OutputConfig(outputToken, guideDays),
            )
        }
    }
}
