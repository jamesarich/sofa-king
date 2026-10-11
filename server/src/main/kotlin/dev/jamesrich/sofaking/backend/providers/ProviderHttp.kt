package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.UpstreamRejectedException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readBuffer
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One answered request. [body] is decompressed when it arrived gzip-encoded by content, whatever the headers said.
 * [cookies] are the `name=value` pairs the response set, for the services whose API needs the web session's cookies.
 */
class Fetched(
    val status: Int, val body: ByteArray, val contentType: String?, val url: String, val location: String? = null,
    val cookies: List<String> = emptyList(),
) {
    val ok: Boolean get() = status in 200..299

    fun text(): String = body.toString(Charsets.UTF_8)

    fun json(): JsonElement = try {
        lenientJson.parseToJsonElement(text())
    } catch (e: Exception) {
        throw ProviderFailure("Response is not JSON", cause = e)
    }

    /** Fails as a transient provider fault unless the status is 2xx; 404 and 410 mean the thing is gone. */
    fun require(what: String): Fetched {
        if (ok) return this
        throw ProviderFailure("$what answered HTTP $status", transient = status != 404 && status != 410)
    }
}

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Provider API traffic: GET and POST under the upstream address policy, redirects followed by hand (the shared
 * client never follows them), bodies bounded, gzip recognized by its magic bytes. Headers go to every hop, so
 * callers pass only what the service's own web client sends.
 */
class ProviderHttp(private val http: HttpClient, private val policy: UpstreamPolicy) {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), limit: Int = DEFAULT_LIMIT, follow: Boolean = true): Fetched =
        request(HttpMethod.Get, url, headers, null, null, limit, follow)

    suspend fun post(url: String, headers: Map<String, String> = emptyMap(), body: String, contentType: String = "application/json", limit: Int = DEFAULT_LIMIT): Fetched =
        request(HttpMethod.Post, url, headers, body, contentType, limit, follow = false)

    private suspend fun request(
        method: HttpMethod, url: String, headers: Map<String, String>, body: String?, contentType: String?, limit: Int, follow: Boolean,
    ): Fetched {
        var target = try { URI(url) } catch (_: Exception) { throw ProviderFailure("Invalid provider URL", transient = false) }
        var redirects = 0
        while (true) {
            try {
                policy.check(target)
            } catch (e: UpstreamRejectedException) {
                throw ProviderFailure("Provider address refused", transient = false, cause = e)
            }
            val hop = target
            val result = try {
                http.prepareRequest(hop.toString()) {
                    this.method = method
                    headers.forEach { (key, value) -> header(key, value) }
                    if (body != null) {
                        contentType(ContentType.parse(contentType ?: "application/json"))
                        setBody(body)
                    }
                }.execute { response ->
                    val status = response.status.value
                    val location = response.headers[HttpHeaders.Location]
                    val cookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty().map { it.substringBefore(';').trim() }.filter { '=' in it }
                    if (status in REDIRECTS && location != null && follow) {
                        response.bodyAsChannel().cancel(null)
                        Fetched(status, ByteArray(0), null, hop.toString(), location, cookies)
                    } else {
                        val bytes = response.bodyAsChannel().readBuffer(limit.toLong() + 1).readByteArray()
                        if (bytes.size > limit) throw ProviderFailure("Provider response exceeds ${limit / 1024} KiB")
                        Fetched(status, gunzipped(bytes, limit), response.headers[HttpHeaders.ContentType], hop.toString(), location, cookies)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderFailure) {
                throw e
            } catch (e: Exception) {
                // Exception messages can carry the URL and so a token; keep the class, drop the text.
                throw ProviderFailure("Provider request failed (${e.javaClass.simpleName})", cause = e)
            }
            if (result.status !in REDIRECTS || result.location == null || !follow) return result
            if (++redirects > 5) throw ProviderFailure("Too many provider redirects")
            val next = try { hop.resolve(result.location) } catch (_: IllegalArgumentException) { throw ProviderFailure("Invalid provider redirect", transient = false) }
            if (hop.scheme == "https" && next.scheme != "https") throw ProviderFailure("Insecure provider redirect", transient = false)
            target = next
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 32 * 1024 * 1024
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** The inflated size is bounded by [limit] too: a small gzip can expand far past the compressed cap. */
        internal fun gunzipped(bytes: ByteArray, limit: Int = DEFAULT_LIMIT): ByteArray =
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                val inflated = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readNBytes(limit + 1) }
                if (inflated.size > limit) throw ProviderFailure("Provider response exceeds ${limit / 1024} KiB once inflated")
                inflated
            } else {
                bytes
            }
    }
}

/** Lenient JSON navigation: a missing key, a null or the wrong shape reads as null, never throws. */
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }

internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)?.takeUnless { it is JsonNull }

internal val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.trim()

internal val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()

internal val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()

internal val JsonElement?.array: List<JsonElement> get() = (this as? JsonArray)?.toList().orEmpty()

internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject

internal fun JsonElement?.str(key: String): String? = this[key].string

/** True for JSON `true`, the string "true" and the string "True". */
internal val JsonElement?.truthy: Boolean get() = (this as? JsonPrimitive)?.let { runCatching { it.jsonPrimitive.content }.getOrNull()?.lowercase() == "true" } == true

/** An HTTP(S) URL or null; providers publish relative paths and empty strings where a logo should be. */
internal fun httpOrNull(value: String?): String? = value?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(2048)
