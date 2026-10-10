package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class FeedConfig(
    val id: String, val playlistUrl: String, val guideUrl: String? = null,
    val headers: Map<String, String> = emptyMap(), val refreshSeconds: Long = 900,
    val allowEmpty: Boolean = false, val probe: Boolean = true, val defaultProtocol: String? = null,
) {
    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Invalid provider ID" }
        httpUri(playlistUrl); guideUrl?.let(::httpUri)
        require(defaultProtocol == null || defaultProtocol in setOf("hls", "mpegts")) { "Unsupported default protocol" }
        require(refreshSeconds >= 60) { "Refresh interval must be at least 60 seconds" }
        require(headers.all { (key, value) -> key.matches(Regex("[A-Za-z0-9-]+")) && value.none { it == '\r' || it == '\n' } })
        require(headers.keys.none { it.lowercase() in setOf("host", "content-length", "connection", "transfer-encoding") })
    }
}
fun loadFeeds(path: Path?): List<FeedConfig> {
    if (path == null) return emptyList()
    require(Files.size(path) <= 1_048_576) { "Source configuration is too large" }
    return Json.decodeFromString<List<FeedConfig>>(Files.readString(path)).also { feeds ->
        require(feeds.map { it.id }.distinct().size == feeds.size) { "Duplicate provider IDs" }
        feeds.forEach { it.validate() }
    }
}
fun httpUri(value: String): URI = URI(value).also {
    require(it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null) { "Expected an HTTP(S) URL without user info" }
}
@Serializable data class Offering(val id: String, val channelId: String, val providerId: String, val url: String, val protocol: String)
@Serializable data class FeedSnapshot(val providerId: String, val channels: List<Channel>, val offerings: List<Offering>, val programmes: List<Programme>, val importedAt: String)
fun stableId(value: String): String = java.util.UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
