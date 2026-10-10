package dev.jamesrich.sofaking.backend

import java.nio.file.Path

data class ServerConfig(val host: String, val port: Int, val dataDirectory: Path) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): ServerConfig {
            val host = environment["SOFA_HOST"] ?: "127.0.0.1"
            require(host.isNotBlank()) { "SOFA_HOST must not be blank" }
            val port = (environment["SOFA_PORT"] ?: "8080").toIntOrNull()
            require(port != null && port in 1..65535) { "SOFA_PORT must be between 1 and 65535" }
            val directory = environment["SOFA_DATA_DIR"] ?: "data"
            require(directory.isNotBlank()) { "SOFA_DATA_DIR must not be blank" }
            return ServerConfig(host, port, Path.of(directory))
        }
    }
}
