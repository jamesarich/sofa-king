package dev.jamesrich.sofaking.backend.setup

import dev.jamesrich.sofaking.backend.api.GroupPattern
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The group map the server ships, `policy/starter-groups.json`: about 200 raw category names the free services use,
 * lowercase, to 22 groups, the broad categories that vote only when nothing more specific does, and name patterns.
 * A policy run consults [map] and [weak] after the policy's own, unless `groups.builtIn` is false, and the starter
 * policy copies all three.
 */
@Serializable class DefaultGroups(private val groupMap: Map<String, String> = emptyMap(), val weak: Set<String> = emptySet(), val patterns: List<GroupPattern> = emptyList()) {
    /** Lowercase raw category to group. */
    val map: Map<String, String> by lazy { groupMap.entries.associate { (raw, group) -> raw.trim().lowercase() to group } }

    /** The group for [raw], a category as a service wrote it, when the shipped map names it. */
    fun groupOf(raw: String): String? = map[raw.trim().lowercase()]

    fun isWeak(raw: String): Boolean = raw.trim().lowercase() in weak

    companion object {
        private val json = Json { ignoreUnknownKeys = false }
        private val loaded by lazy {
            val text = DefaultGroups::class.java.getResourceAsStream("/policy/starter-groups.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("policy/starter-groups.json is missing from the server")
            json.decodeFromString<DefaultGroups>(text).let { DefaultGroups(it.map, it.weak.mapTo(HashSet()) { raw -> raw.trim().lowercase() }, it.patterns) }
        }

        fun load(): DefaultGroups = loaded
    }
}
