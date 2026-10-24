package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.ServerConfig.Companion.DEFAULT_GUIDE_DAYS
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.DEFAULT_PLEX_SYNC_MINUTES
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.DEFAULT_TUNERS
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.DEFAULT_UPDATE_IMAGE
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.DEVICE_NAME_LIMIT
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.DEVICE_SIZE_RANGE
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.EXTRA_PORTS_LIMIT
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.GUIDE_DAYS_RANGE
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.PLEX_SYNC_MINUTES_RANGE
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.PLEX_TOKEN_MIN_LENGTH
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.PORT_RANGE
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.TOKEN_MIN_LENGTH
import dev.jamesrich.sofaking.backend.http.AddressAllowList
import dev.jamesrich.sofaking.backend.library.TmdbConfig
import dev.jamesrich.sofaking.backend.update.UpdateChecker
import java.net.URI
import java.nio.file.Path

/** Reads and checks the variables of [values]; a value the server cannot start with throws. */
internal class EnvironmentReader(private val values: Map<String, String>) {
    operator fun get(name: String): String? = values[name]

    /** [name]'s whole number, [default] when it is unset; one outside [range] stops the start. */
    fun number(name: String, default: Int, range: IntRange): Int {
        val value = (values[name] ?: default.toString()).toIntOrNull()
        require(value != null && value in range) { "$name must be between ${range.first} and ${range.last}" }
        return value
    }

    fun flag(name: String, default: Boolean): Boolean {
        val value = values[name]?.lowercase() ?: return default
        require(value in setOf("true", "false")) { "$name must be true or false" }
        return value == "true"
    }

    fun path(name: String): Path? = values[name]?.let {
        require(it.isNotBlank()) { "$name must not be blank" }
        Path.of(it)
    }

    fun token(name: String): String? = values[name]?.also {
        require(it.length >= TOKEN_MIN_LENGTH) { "$name must contain at least $TOKEN_MIN_LENGTH characters" }
    }

    /** [name] trimmed; null when it is unset or empty. */
    fun text(name: String): String? = values[name]?.trim()?.takeIf { it.isNotEmpty() }

    /** [name]'s comma-separated entries, trimmed, without empty ones. */
    fun list(name: String): List<String> =
        values[name].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

private val RESTREAM_ORIGIN = Regex("[a-z0-9.\\-\\[\\]:]+")

internal fun restreamOrigins(env: EnvironmentReader): Set<String> {
    val origins = env.list("SOFA_RESTREAM_ORIGINS").map { it.lowercase() }.toSet()
    require(origins.all { it.matches(RESTREAM_ORIGIN) }) { "SOFA_RESTREAM_ORIGINS entries must be host or host:port" }
    return origins
}

internal fun outputConfig(env: EnvironmentReader): OutputConfig {
    val token = env["SOFA_OUTPUT_TOKEN"]
    require(token == null || (token.length >= TOKEN_MIN_LENGTH && token.all { it.isUrlSafe() })) {
        "SOFA_OUTPUT_TOKEN must contain at least $TOKEN_MIN_LENGTH letters, digits, hyphens or underscores"
    }
    return OutputConfig(token, env.number("SOFA_XMLTV_DAYS", DEFAULT_GUIDE_DAYS, GUIDE_DAYS_RANGE))
}

private fun Char.isUrlSafe() = isLetterOrDigit() || this == '-' || this == '_'

/** Distinct ports, each a valid one. */
private fun List<Int>.distinctPorts() = all { it in PORT_RANGE } && distinct() == this

private val DEVICE_ID = Regex("[0-9A-F]{8}")

/** The HDHomeRun devices: at most [capacity] tuners, the first on [port]. */
internal fun hdhrConfig(env: EnvironmentReader, port: Int, capacity: Int, enabled: Boolean): HdhrConfig {
    val tuners = (env["SOFA_HDHR_TUNERS"] ?: DEFAULT_TUNERS.toString()).toIntOrNull()
    require(tuners != null && tuners in 1..capacity) { "SOFA_HDHR_TUNERS must be between 1 and SOFA_MAX_SESSIONS" }
    val deviceId = env["SOFA_HDHR_DEVICE_ID"]?.uppercase()
    require(deviceId == null || deviceId.matches(DEVICE_ID)) { "SOFA_HDHR_DEVICE_ID must be eight hex digits" }
    val name = env["SOFA_HDHR_NAME"] ?: "Sofa King"
    require(name.isNotBlank() && name.length <= DEVICE_NAME_LIMIT && name.none { it < ' ' }) {
        "SOFA_HDHR_NAME must be 1 to $DEVICE_NAME_LIMIT printable characters"
    }
    val allow = env.list("SOFA_HDHR_ALLOW")
    AddressAllowList(allow)
    val extraPorts = env.list("SOFA_HDHR_EXTRA_PORTS").map { it.toIntOrNull() ?: 0 }
    require(extraPorts.distinctPorts() && port !in extraPorts && extraPorts.size <= EXTRA_PORTS_LIMIT) {
        "SOFA_HDHR_EXTRA_PORTS must list up to $EXTRA_PORTS_LIMIT distinct ports other than SOFA_PORT"
    }
    val publicPorts = env.list("SOFA_HDHR_PUBLIC_PORTS").map { it.toIntOrNull() ?: 0 }
    val devices = 1 + extraPorts.size
    require(publicPorts.isEmpty() || (publicPorts.size == devices && publicPorts.distinctPorts())) {
        "SOFA_HDHR_PUBLIC_PORTS must list one distinct port per device: SOFA_PORT's, then each of SOFA_HDHR_EXTRA_PORTS"
    }
    val deviceSize = env.number("SOFA_HDHR_DEVICE_SIZE", HdhrConfig.DEFAULT_DEVICE_SIZE, DEVICE_SIZE_RANGE)
    return HdhrConfig(enabled, tuners, deviceId, name, allow, extraPorts, deviceSize, publicPorts)
}

/** A token of at least [length] characters, none of them a space or a control character. */
private fun printableToken(token: String, length: Int) =
    token.length >= length && token.none { it <= ' ' || it == '\u007f' }

internal fun plexConfig(env: EnvironmentReader, hdhrEnabled: Boolean): PlexConfig? {
    val url = env["SOFA_PLEX_URL"]
    val token = env["SOFA_PLEX_TOKEN"]
    require((url == null) == (token == null)) { "SOFA_PLEX_URL and SOFA_PLEX_TOKEN must be set together" }
    if (url == null || token == null) return null
    require(hdhrEnabled) { "SOFA_PLEX_URL requires SOFA_HDHR_ENABLED" }
    require(printableToken(token, PLEX_TOKEN_MIN_LENGTH)) {
        "SOFA_PLEX_TOKEN must contain at least $PLEX_TOKEN_MIN_LENGTH printable characters without spaces"
    }
    val minutes = env.number("SOFA_PLEX_SYNC_MINUTES", DEFAULT_PLEX_SYNC_MINUTES, PLEX_SYNC_MINUTES_RANGE)
    val base = env["SOFA_PLEX_TUNER_BASE"]?.let { origin(it, "SOFA_PLEX_TUNER_BASE", withPort = false) }
    return PlexConfig(origin(url, "SOFA_PLEX_URL", withPort = true), token, minutes, base)
}

/** [value] as `scheme://host[:port]`, rejecting paths, queries and credentials. */
private fun origin(value: String, name: String, withPort: Boolean): String {
    val uri = runCatching { URI(value.trim().removeSuffix("/")) }.getOrNull()
    require(
        uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null &&
            uri.rawFragment == null && (withPort || uri.port == -1),
    ) {
        if (withPort) "$name must be http(s)://host[:port]" else "$name must be http(s)://host without a port"
    }
    return "${uri.scheme.lowercase()}://${uri.rawAuthority}"
}

private val LANGUAGE_TAG = Regex("[a-z]{2,3}(-[A-Z]{2})?")

internal fun tmdbConfig(env: EnvironmentReader): TmdbConfig? {
    val language = env["SOFA_TMDB_LANGUAGE"]?.trim()
    require(language == null || language.matches(LANGUAGE_TAG)) {
        "SOFA_TMDB_LANGUAGE must be a language tag such as en-US"
    }
    val token = env.text("SOFA_TMDB_TOKEN") ?: return null
    require(printableToken(token, TOKEN_MIN_LENGTH)) {
        "SOFA_TMDB_TOKEN must be a TMDB API read access token or API key"
    }
    return TmdbConfig(token, language ?: "en-US")
}

/** `SOFA_UPDATE_IMAGE`: [DEFAULT_UPDATE_IMAGE] when it is unset, null when it is set empty. */
internal fun updateImage(env: EnvironmentReader): String? {
    if (env["SOFA_UPDATE_IMAGE"] == null) return DEFAULT_UPDATE_IMAGE
    return env.text("SOFA_UPDATE_IMAGE")?.also { UpdateChecker.ImageRef.parse(it) }
}

/** `SOFA_COMSKIP`, `comskip` when it is unset; null when it is `none`. */
internal fun comskip(env: EnvironmentReader): String? {
    val comskip = (env["SOFA_COMSKIP"] ?: "comskip").trim()
    require(comskip.isNotEmpty()) { "SOFA_COMSKIP must not be blank; set it to none to turn commercial detection off" }
    return comskip.takeUnless { it == "none" }
}
