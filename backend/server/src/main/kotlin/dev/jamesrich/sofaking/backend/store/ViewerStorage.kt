package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.Viewer
import dev.jamesrich.sofaking.contract.ViewerPreferences
import java.time.Instant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** A stored document may carry fields this server's contract does not declare. */
private val viewerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Every viewer, most recently seen first. */
suspend fun BackendStore.viewers(): List<Viewer> = query {
    Viewers.selectAll().map { it.viewer() }.sortedByDescending { it.lastSeenAt }
}

/** The viewer [clientId], or null when it never paired or saved preferences. */
suspend fun BackendStore.viewer(clientId: String): Viewer? = query {
    Viewers.selectAll().where { Viewers.clientId eq clientId }.singleOrNull()?.viewer()
}

/**
 * Records that [clientId] was seen [now], creating it with [name] and [platform] when new; a non-empty label
 * replaces the stored one, so a renamed device shows its new name. Preferences are left as they are.
 */
suspend fun BackendStore.touchViewer(clientId: String, name: String, platform: String, now: Instant) = query {
    val at = now.toString()
    val inserted = Viewers.insertIgnore {
        it[Viewers.clientId] = clientId
        it[Viewers.name] = name
        it[Viewers.platform] = platform
        it[firstSeenAt] = at
        it[lastSeenAt] = at
    }.insertedCount
    if (inserted == 0) Viewers.update({ Viewers.clientId eq clientId }) {
        it[lastSeenAt] = at
        if (name.isNotEmpty()) it[Viewers.name] = name
        if (platform.isNotEmpty()) it[Viewers.platform] = platform
    }
    Unit
}

/** Replaces [clientId]'s preferences with [preferences], stamped [now]; the stored document is returned. */
suspend fun BackendStore.saveViewerPreferences(clientId: String, preferences: ViewerPreferences, now: Instant): ViewerPreferences = query {
    val at = now.toString()
    val existing = Viewers.selectAll().where { Viewers.clientId eq clientId }.singleOrNull()
    val name = preferences.name.ifEmpty { existing?.get(Viewers.name).orEmpty() }
    val platform = preferences.platform.ifEmpty { existing?.get(Viewers.platform).orEmpty() }
    val stored = preferences.copy(name = name, platform = platform, updatedAt = at)
    val json = viewerJson.encodeToString(ViewerPreferences.serializer(), stored)
    if (existing == null) Viewers.insertIgnore {
        it[Viewers.clientId] = clientId
        it[Viewers.name] = name
        it[Viewers.platform] = platform
        it[firstSeenAt] = at
        it[lastSeenAt] = at
        it[preferencesJson] = json
        it[updatedAt] = at
    } else Viewers.update({ Viewers.clientId eq clientId }) {
        it[Viewers.name] = name
        it[Viewers.platform] = platform
        it[lastSeenAt] = at
        it[preferencesJson] = json
        it[updatedAt] = at
    }
    stored
}

/** Forgets [clientId]: its label and preferences. The client keeps working and reappears when it next saves. */
suspend fun BackendStore.forgetViewer(clientId: String): Boolean = query {
    Viewers.deleteWhere { Viewers.clientId eq clientId } > 0
}

private fun ResultRow.viewer(): Viewer {
    val preferences = this[Viewers.preferencesJson]?.let { json ->
        runCatching { viewerJson.decodeFromString(ViewerPreferences.serializer(), json) }.getOrNull()
    }
    return Viewer(
        this[Viewers.clientId], this[Viewers.name], this[Viewers.platform], this[Viewers.firstSeenAt], this[Viewers.lastSeenAt],
        preferences?.copy(name = this[Viewers.name], platform = this[Viewers.platform], updatedAt = this[Viewers.updatedAt].orEmpty()),
    )
}
