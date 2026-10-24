package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/** The ignored overview items: each item's key to the fingerprint of its text when it was ignored. */
suspend fun BackendStore.ignoredAttention(): Map<String, String> = read {
    AttentionIgnores.selectAll().associate { it[AttentionIgnores.itemKey] to it[AttentionIgnores.fingerprint] }
}

/** Ignores the item [key] while its text keeps [fingerprint]. */
suspend fun BackendStore.ignoreAttention(key: String, fingerprint: String, now: Instant) = query {
    AttentionIgnores.upsert {
        it[itemKey] = key
        it[AttentionIgnores.fingerprint] = fingerprint
        it[ignoredAt] = now.toString()
    }
    Unit
}

/** Shows the item [key] again. */
suspend fun BackendStore.showAttention(key: String) = query {
    AttentionIgnores.deleteWhere { itemKey eq key }
    Unit
}
