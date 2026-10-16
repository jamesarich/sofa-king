package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.io.atomicWrite
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Where a token the server runs with came from. */
enum class SecretOrigin { ENVIRONMENT, FILE, GENERATED }

/** The secrets that `NAME_FILE` can supply instead of `NAME`. */
internal val FILE_SECRETS = listOf("SOFA_API_TOKEN", "SOFA_ADMIN_TOKEN", "SOFA_OUTPUT_TOKEN", "SOFA_PLEX_TOKEN")

/**
 * [environment] with each `NAME_FILE` replaced by `NAME` holding the file's contents without trailing line breaks,
 * and the origin of every secret that is set. Error messages name the variables and the path, never the contents.
 */
internal fun readSecretFiles(environment: Map<String, String>, read: (Path) -> String): Pair<Map<String, String>, Map<String, SecretOrigin>> {
    val resolved = environment.toMutableMap()
    val origins = mutableMapOf<String, SecretOrigin>()
    for (name in FILE_SECRETS) {
        val file = environment["${name}_FILE"]
        if (file == null) {
            if (environment[name] != null) origins[name] = SecretOrigin.ENVIRONMENT
            continue
        }
        require(environment[name] == null) { "Set $name or ${name}_FILE, not both" }
        require(file.isNotBlank()) { "${name}_FILE must not be blank" }
        val value =
            try { read(Path.of(file)) }
            catch (e: IOException) { throw IllegalArgumentException("${name}_FILE names $file, which cannot be read (${e.javaClass.simpleName})") }
        resolved.remove("${name}_FILE")
        resolved[name] = value.trimEnd('\n', '\r')
        origins[name] = SecretOrigin.FILE
    }
    return resolved to origins
}

/**
 * The tokens the server generated for itself, kept in one JSON file in the data directory that only its owner can read.
 * The file is replaced by an atomic rename, so a crash leaves the old file or the new one.
 */
internal class SecretsFile(val path: Path, private val random: SecureRandom = SecureRandom()) {
    private val prettyJson = Json { prettyPrint = true }
    /** The stored value of each of [names] (`apiToken`, `adminToken`), generating and storing those missing; the second set names the generated ones. */
    fun resolve(names: List<String>): Pair<Map<String, String>, Set<String>> {
        val stored = read().toMutableMap()
        val generated = names.filter { it !in stored }.toSet()
        for (name in generated) stored[name] = HexFormat.of().formatHex(ByteArray(32).also(random::nextBytes))
        if (generated.isNotEmpty()) write(stored)
        return names.associateWith { stored.getValue(it) } to generated
    }

    private fun read(): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        val text =
            try { Files.readString(path) }
            catch (e: IOException) { throw IllegalArgumentException("$path cannot be read (${e.javaClass.simpleName}); it must belong to the user the server runs as") }
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw IllegalArgumentException("$path is not a JSON object; delete it to generate new tokens")
        return json.mapValues { (name, value) ->
            val token = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            require(token != null && token.length >= 32 && token.none { it.isWhitespace() }) { "$path holds an invalid $name; delete that entry to generate a new one" }
            token
        }
    }

    private fun write(values: Map<String, String>) {
        val bytes = (prettyJson.encodeToString(JsonObject.serializer(), JsonObject(values.mapValues { JsonPrimitive(it.value) })) + "\n").toByteArray()
        val directory = path.toAbsolutePath().parent
        try {
            Files.createDirectories(directory)
            atomicWrite(path, bytes)
        } catch (_: AccessDeniedException) {
            throw IllegalArgumentException("$directory is not writable by the user the server runs as, so ${path.fileName} cannot be stored there")
        }
    }

    companion object {
        const val NAME = "secrets.json"
    }
}
