package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.NamedOutput
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GROUP_LIMIT
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.namedOutputs
import dev.jamesrich.sofaking.backend.store.saveNamedOutputs
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A name in an output's URLs: lowercase letters, digits and hyphens, up to 32. */
private val OUTPUT_NAME = Regex("[a-z0-9][a-z0-9-]{0,31}")

/** Names the `/output` routes already use. */
private val RESERVED = setOf("stream")

private const val MAX_OUTPUTS = 32
private const val MAX_CHANNELS = 5000
private const val CHANNEL_REF_LIMIT = 64

/** The named player outputs, kept in the database; each change replaces the list in one write. */
class NamedOutputs(private val store: BackendStore) {
    private val mutex = Mutex()

    suspend fun list(): List<NamedOutput> = store.namedOutputs()

    suspend fun get(name: String): NamedOutput? = list().firstOrNull { it.name == name }

    /** Saves [output], replacing the one of its name; refused with 400 when it is not valid. */
    suspend fun put(output: NamedOutput): NamedOutput = mutex.withLock {
        val cleaned = validated(output)
        val outputs = list()
        if (outputs.none { it.name == cleaned.name } && outputs.size >= MAX_OUTPUTS) {
            invalid("The server keeps at most $MAX_OUTPUTS outputs")
        }
        store.saveNamedOutputs((outputs.filterNot { it.name == cleaned.name } + cleaned).sortedBy { it.name })
        cleaned
    }

    /** Deletes the output named [name]; false when there was none. */
    suspend fun delete(name: String): Boolean = mutex.withLock {
        val outputs = list()
        if (outputs.none { it.name == name }) return@withLock false
        store.saveNamedOutputs(outputs.filterNot { it.name == name })
        true
    }
}

/** Whether [output] holds [entry]: its group is one of the output's, or its number or ID is listed. */
internal fun NamedOutput.holds(entry: LineupEntryRecord?): Boolean {
    if (entry == null) return false
    val group = entry.group
    return (group != null && groups.any { it.equals(group, ignoreCase = true) }) ||
        entry.number in channels || entry.id in channels
}

private fun validated(output: NamedOutput): NamedOutput {
    val name = output.name.trim().lowercase()
    if (!OUTPUT_NAME.matches(name) || name in RESERVED) {
        invalid("An output name is 1 to 32 lowercase letters, digits and hyphens, and not stream")
    }
    val groups = output.groups.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    val channels = output.channels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (groups.isEmpty() && channels.isEmpty()) invalid("An output needs at least one group or channel")
    if (groups.any { it.length > GROUP_LIMIT }) invalid("A group is at most $GROUP_LIMIT characters")
    if (channels.size > MAX_CHANNELS || channels.any { it.length > CHANNEL_REF_LIMIT }) {
        invalid("An output lists at most $MAX_CHANNELS channels of at most $CHANNEL_REF_LIMIT characters")
    }
    return NamedOutput(name, groups, channels)
}

private fun invalid(message: String): Nothing = throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", message)
