package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.io.atomicWrite
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalSerializationApi::class)
private val writtenJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** [tree] as the server writes a policy file. */
internal fun policyBytes(tree: JsonObject): ByteArray = (
    writtenJson.encodeToString(JsonObject.serializer(), tree) + "\n"
    ).toByteArray()

/**
 * The policy file and every write to it. One mutex serializes writers. A write checks the file on disk against the
 * loaded revision, and when it differs reloads it and fails with 409 so no edit lands on a revision nobody read.
 * Writes go to a temp file in the same directory, are fsynced and renamed over the policy. Never variable-expanded.
 */
class PolicyFile(initial: LoadedPolicy, private val providerIds: Set<String>, private val onReloaded: () -> Unit = {}) {
    private val log = LoggerFactory.getLogger(PolicyFile::class.java)
    private val mutex = Mutex()
    private val loaded = AtomicReference(initial)

    val path = initial.path

    fun current(): LoadedPolicy = loaded.get()

    /** Replaces the file with [bytes] when [ifMatch] is the loaded revision. */
    suspend fun replace(ifMatch: String, bytes: ByteArray): LoadedPolicy = write(ifMatch) { bytes }

    /** Rewrites the file's JSON through [edit]; [ifMatch], when given, must be the loaded revision. */
    suspend fun edit(ifMatch: String?, edit: (JsonObject) -> JsonObject): LoadedPolicy = write(ifMatch, editing(edit))

    private fun editing(edit: (JsonObject) -> JsonObject): (ByteArray) -> ByteArray = { current ->
        val tree =
            Json.parseToJsonElement(current.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw ApiFailure(HttpStatusCode.Conflict, "POLICY_CHANGED", "The policy file is not a JSON object")
        policyBytes(edit(tree))
    }

    /**
     * The policy [edit] would write, checked as a write checks it ([ifMatch] too) but not written, for a preview of
     * its effect.
     */
    suspend fun candidate(ifMatch: String?, edit: (JsonObject) -> JsonObject): LineupPolicy = mutex.withLock {
        withContext(Dispatchers.IO) { prepared(ifMatch, editing(edit)).policy }
    }

    private suspend fun write(ifMatch: String?, produce: (ByteArray) -> ByteArray): LoadedPolicy = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = prepared(ifMatch, produce)
            atomicWrite(path, next.bytes)
            LoadedPolicy(path, next.policy, policyRevision(next.bytes)).also { loaded.set(it) }
        }
    }

    private class Prepared(val bytes: ByteArray, val policy: LineupPolicy)

    /** The next file and its policy, once the loaded revision is the one on disk and [ifMatch], and it validates. */
    private fun prepared(ifMatch: String?, produce: (ByteArray) -> ByteArray): Prepared {
        val held = loaded.get()
        val onDisk = Files.readAllBytes(path)
        if (policyRevision(onDisk) != held.revision) {
            reload()
            throw ApiFailure(
                HttpStatusCode.Conflict,
                "POLICY_CHANGED",
                "The policy file changed on disk; it was reloaded, read it again and retry",
            )
        }
        if (ifMatch != null && ifMatch != held.revision) {
            throw ApiFailure(
                HttpStatusCode.Conflict,
                "POLICY_CHANGED",
                "The policy revision is ${held.revision}, not $ifMatch",
            )
        }
        val next = produce(onDisk)
        if (next.size >
            POLICY_LIMIT
        ) {
            throw ApiFailure(HttpStatusCode.PayloadTooLarge, "REQUEST_TOO_LARGE", "The policy exceeds 4 MiB")
        }
        val policy = try {
            parsePolicy(next)
        } catch (e: IllegalArgumentException) {
            // A SerializationException is one: JSON that does not parse, or does not fit the schema.
            throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_POLICY",
                "The policy is not valid JSON for this schema: " +
                    "${e.message?.lineSequence()?.firstOrNull().orEmpty()}",
                cause = e,
            )
        }
        val errors = validatePolicy(policy, providerIds)
        if (errors.isNotEmpty()) {
            throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_POLICY",
                errors.joinToString("; "),
            )
        }
        return Prepared(next, policy)
    }

    private fun reload() {
        val policy = try {
            loadPolicy(path, providerIds)
        } catch (e: IOException) {
            return keep(e)
        } catch (e: IllegalArgumentException) {
            // Too large, not JSON for the schema, or failing validation.
            return keep(e)
        }
        loaded.set(policy)
        log.info("lineup policy reloaded from disk revision={}", policy.revision)
        onReloaded()
    }

    private fun keep(failure: Exception) = log.warn(
        "lineup policy on disk is invalid; keeping revision {}: {}",
        loaded.get().revision,
        failure.message?.lineSequence()?.firstOrNull(),
    )
}
