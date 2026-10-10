package dev.jamesrich.sofaking.backend

import java.nio.file.Path

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
            )
        }
    }
}
