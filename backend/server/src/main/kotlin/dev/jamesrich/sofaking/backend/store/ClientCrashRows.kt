package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.Table

/** Crashes the apps reported, newest [KEPT_CRASHES] kept. */
internal object ClientCrashRows : Table("client_crash") {
    val id = text("id")
    val clientId = text("client_id").nullable()
    val clientName = text("client_name")
    val receivedAt = text("received_at")
    val occurredAt = text("occurred_at")
    val exception = text("exception")
    val message = text("message")
    val frames = text("frames")
    val appVersion = text("app_version")
    val platform = text("platform")
    val osVersion = text("os_version")
    val device = text("device")
    override val primaryKey = PrimaryKey(id)
}
