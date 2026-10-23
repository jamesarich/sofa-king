package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/** The portal sessions that outlive [now], after deleting the expired ones. */
suspend fun BackendStore.adminSessions(now: Instant): List<StoredAdminSession> = query {
    AdminSessionRows.deleteWhere { expiresAt lessEq now.toString() }
    AdminSessionRows.selectAll().map {
        StoredAdminSession(
            it[AdminSessionRows.key],
            it[AdminSessionRows.csrf],
            Instant.parse(it[AdminSessionRows.expiresAt]),
        )
    }
}

suspend fun BackendStore.saveAdminSession(session: StoredAdminSession) = query {
    AdminSessionRows.insert {
        it[key] = session.key
        it[csrf] = session.csrf
        it[expiresAt] = session.expiresAt.toString()
    }
    Unit
}

suspend fun BackendStore.deleteAdminSession(key: String) = query {
    AdminSessionRows.deleteWhere { AdminSessionRows.key eq key }
    Unit
}
