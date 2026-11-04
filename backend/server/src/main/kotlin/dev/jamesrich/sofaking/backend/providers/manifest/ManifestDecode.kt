package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ProviderHttp
import dev.jamesrich.sofaking.backend.providers.jsonArrayAfter
import dev.jamesrich.sofaking.backend.providers.lenientJson
import dev.jamesrich.sofaking.backend.providers.nextData
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

internal const val JSON_ARRAY_AFTER = "jsonArrayAfter:"

/** The JSON that [steps] make of a response [body]; a step that yields JSON ends the chain. */
internal fun decodeBody(body: ByteArray, steps: List<String>, what: String): JsonElement {
    var bytes = body
    for (step in steps) {
        finalStep(step, bytes, what)?.let { return it }
        bytes = when (step) {
            "base64" -> fromBase64(bytes, what)
            "zlib" -> inflate(bytes, what)
            "gzip" -> gunzip(bytes, what)
            else -> bytes
        }
    }
    return parse(bytes)
}

/** The JSON a step that ends the chain makes of [bytes]; null for a step that only transforms them. */
private fun finalStep(step: String, bytes: ByteArray, what: String): JsonElement? = when {
    step == "nextData" -> nextData(bytes.toString(Charsets.UTF_8), what)
    step.startsWith(JSON_ARRAY_AFTER) -> arrayAfter(bytes, step.removePrefix(JSON_ARRAY_AFTER), what)
    else -> null
}

private fun arrayAfter(bytes: ByteArray, key: String, what: String): JsonElement =
    jsonArrayAfter(bytes.toString(Charsets.UTF_8), key) ?: throw ProviderFailure("$what has no $key array")

private fun parse(bytes: ByteArray): JsonElement = try {
    lenientJson.parseToJsonElement(bytes.toString(Charsets.UTF_8))
} catch (e: SerializationException) {
    throw ProviderFailure("Response is not JSON", cause = e)
}

private fun fromBase64(bytes: ByteArray, what: String): ByteArray = try {
    Base64.getDecoder().decode(bytes.toString(Charsets.UTF_8).trim())
} catch (e: IllegalArgumentException) {
    throw ProviderFailure("$what is not base64", cause = e)
}

private fun gunzip(bytes: ByteArray, what: String): ByteArray = try {
    GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readNBytes(ProviderHttp.DEFAULT_LIMIT + 1) }
} catch (e: IOException) {
    throw ProviderFailure("$what did not gunzip", cause = e)
}.also { if (it.size > ProviderHttp.DEFAULT_LIMIT) throw ProviderFailure("$what is too large") }

private fun inflate(compressed: ByteArray, what: String): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream(compressed.size * 4)
        val buffer = ByteArray(65_536)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, count)
            if (out.size() > ProviderHttp.DEFAULT_LIMIT) throw ProviderFailure("$what is too large")
        }
        return out.toByteArray()
    } catch (e: DataFormatException) {
        throw ProviderFailure("$what did not inflate", cause = e)
    } finally {
        inflater.end()
    }
}
