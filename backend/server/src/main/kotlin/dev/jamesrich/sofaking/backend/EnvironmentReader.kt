package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.ServerConfig.Companion.DEFAULT_UPDATE_IMAGE
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.DEVICE_NAME_LIMIT
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.EXTRA_PORTS_LIMIT
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.PLEX_TOKEN_MIN_LENGTH
import dev.jamesrich.sofaking.backend.ServerConfig.Companion.TOKEN_MIN_LENGTH
import dev.jamesrich.sofaking.backend.http.AddressAllowList
import dev.jamesrich.sofaking.backend.library.TmdbConfig
import dev.jamesrich.sofaking.backend.settings.SettingDef
import dev.jamesrich.sofaking.backend.settings.Settings
import dev.jamesrich.sofaking.backend.update.UpdateChecker
import java.net.URI
import java.nio.file.Path

/** Reads and checks the variables of [values]; a value the server cannot start with throws. */
internal class EnvironmentReader(private val values: Map<String, String>) {
    operator fun get(name: String): String? = values[name]

    /** [def]'s whole number, its default when it is unset; one outside its range stops the start. */
    fun number(def: SettingDef): Int {
        val value = def.normalize(values[def.name] ?: def.default.orEmpty())?.toInt()
        require(value != null) { def.failure }
        return value
    }

    /** [def]'s choice, its default when it is unset; one it does not list stops the start. */
    fun choice(def: SettingDef): String {
        val value = def.normalize(values[def.name] ?: def.default.orEmpty())
        require(value != null) { def.failure }
        return value
    }

    fun flag(def: SettingDef, default: Boolean = def.default.toBoolean()): Boolean {
        val value = values[def.name]?.lowercase() ?: return default
        require(value in setOf("true", "false")) { def.failure }
        return value == "true"
    }

    fun path(def: SettingDef): Path? = values[def.name]?.let {
        require(it.isNotBlank()) { def.failure }
        Path.of(it)
    }

    fun token(def: SettingDef): String? = values[def.name]?.also {
        require(it.length >= TOKEN_MIN_LENGTH) { def.failure }
    }

    /** [def]'s value trimmed; null when it is unset or empty. */
    fun text(def: SettingDef): String? = values[def.name]?.trim()?.takeIf { it.isNotEmpty() }

    /** [def]'s comma-separated entries, trimmed, without empty ones. */
    fun list(def: SettingDef): List<String> =
        values[def.name].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

private val RESTREAM_ORIGIN = Regex("[a-z0-9.\\-\\[\\]:]+")

internal fun restreamOrigins(env: EnvironmentReader): Set<String> {
    val origins = env.list(Settings.RESTREAM_ORIGINS).map { it.lowercase() }.toSet()
    require(origins.all { it.matches(RESTREAM_ORIGIN) }) { "SOFA_RESTREAM_ORIGINS entries must be host or host:port" }
    return origins
}

internal fun outputConfig(env: EnvironmentReader): OutputConfig {
    val token = env[Settings.OUTPUT_TOKEN.name]
    require(token == null || (token.length >= TOKEN_MIN_LENGTH && token.all { it.isUrlSafe() })) {
        "SOFA_OUTPUT_TOKEN must contain at least $TOKEN_MIN_LENGTH letters, digits, hyphens or underscores"
    }
    return OutputConfig(token, env.number(Settings.XMLTV_DAYS))
}

private fun Char.isUrlSafe() = isLetterOrDigit() || this == '-' || this == '_'

/** Distinct ports, each a valid one. */
private fun List<Int>.distinctPorts() = all { it in Settings.PORT.range } && distinct() == this

private val DEVICE_ID = Regex("[0-9A-F]{8}")

/** The HDHomeRun devices: at most [capacity] tuners, the first on [port]. */
internal fun hdhrConfig(env: EnvironmentReader, port: Int, capacity: Int, enabled: Boolean): HdhrConfig {
    val tuners = (env[Settings.HDHR_TUNERS.name] ?: Settings.HDHR_TUNERS.default.orEmpty()).toIntOrNull()
    require(tuners != null && tuners in 1..capacity) { "SOFA_HDHR_TUNERS must be between 1 and SOFA_MAX_SESSIONS" }
    val deviceId = env[Settings.HDHR_DEVICE_ID.name]?.uppercase()
    require(deviceId == null || deviceId.matches(DEVICE_ID)) { "SOFA_HDHR_DEVICE_ID must be eight hex digits" }
    val name = env[Settings.HDHR_NAME.name] ?: Settings.HDHR_NAME.default.orEmpty()
    require(name.isNotBlank() && name.length <= DEVICE_NAME_LIMIT && name.none { it < ' ' }) {
        "SOFA_HDHR_NAME must be 1 to $DEVICE_NAME_LIMIT printable characters"
    }
    val allow = env.list(Settings.HDHR_ALLOW)
    AddressAllowList(allow)
    val extraPorts = env.list(Settings.HDHR_EXTRA_PORTS).map { it.toIntOrNull() ?: 0 }
    require(extraPorts.distinctPorts() && port !in extraPorts && extraPorts.size <= EXTRA_PORTS_LIMIT) {
        "SOFA_HDHR_EXTRA_PORTS must list up to $EXTRA_PORTS_LIMIT distinct ports other than SOFA_PORT"
    }
    val publicPorts = env.list(Settings.HDHR_PUBLIC_PORTS).map { it.toIntOrNull() ?: 0 }
    val devices = 1 + extraPorts.size
    require(publicPorts.isEmpty() || (publicPorts.size == devices && publicPorts.distinctPorts())) {
        "SOFA_HDHR_PUBLIC_PORTS must list one distinct port per device: SOFA_PORT's, then each of SOFA_HDHR_EXTRA_PORTS"
    }
    val deviceSize = env.number(Settings.HDHR_DEVICE_SIZE)
    return HdhrConfig(enabled, tuners, deviceId, name, allow, extraPorts, deviceSize, publicPorts)
}

/** A token of at least [length] characters, none of them a space or a control character. */
private fun printableToken(token: String, length: Int) =
    token.length >= length && token.none { it <= ' ' || it == '\u007f' }

/** Plex sync when `SOFA_PLEX_URL` and `SOFA_PLEX_TOKEN` are set, syncing every [minutes]. */
internal fun plexConfig(env: EnvironmentReader, hdhrEnabled: Boolean, minutes: Int): PlexConfig? {
    val url = env[Settings.PLEX_URL.name]
    val token = env[Settings.PLEX_TOKEN.name]
    require((url == null) == (token == null)) { "SOFA_PLEX_URL and SOFA_PLEX_TOKEN must be set together" }
    if (url == null || token == null) return null
    require(hdhrEnabled) { "SOFA_PLEX_URL requires SOFA_HDHR_ENABLED" }
    require(printableToken(token, PLEX_TOKEN_MIN_LENGTH)) {
        "SOFA_PLEX_TOKEN must contain at least $PLEX_TOKEN_MIN_LENGTH printable characters without spaces"
    }
    val base = env[Settings.PLEX_TUNER_BASE.name]?.let { origin(it, "SOFA_PLEX_TUNER_BASE", withPort = false) }
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
    val language = env[Settings.TMDB_LANGUAGE.name]?.trim()
    require(language == null || language.matches(LANGUAGE_TAG)) {
        "SOFA_TMDB_LANGUAGE must be a language tag such as en-US"
    }
    val token = env.text(Settings.TMDB_TOKEN) ?: return null
    require(printableToken(token, TOKEN_MIN_LENGTH)) {
        "SOFA_TMDB_TOKEN must be a TMDB API read access token or API key"
    }
    return TmdbConfig(token, language ?: "en-US")
}

/** `SOFA_UPDATE_IMAGE`: [DEFAULT_UPDATE_IMAGE] when it is unset, null when it is set empty. */
internal fun updateImage(env: EnvironmentReader): String? {
    if (env[Settings.UPDATE_IMAGE.name] == null) return DEFAULT_UPDATE_IMAGE
    return env.text(Settings.UPDATE_IMAGE)?.also { UpdateChecker.ImageRef.parse(it) }
}

/** `SOFA_COMSKIP`, `comskip` when it is unset; null when it is `none`. */
internal fun comskip(env: EnvironmentReader): String? {
    val comskip = (env[Settings.COMSKIP.name] ?: Settings.COMSKIP.default.orEmpty()).trim()
    require(comskip.isNotEmpty()) { "SOFA_COMSKIP must not be blank; set it to none to turn commercial detection off" }
    return comskip.takeUnless { it == "none" }
}
