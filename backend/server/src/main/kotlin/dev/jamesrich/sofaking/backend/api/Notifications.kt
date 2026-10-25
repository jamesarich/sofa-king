package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

/**
 * Where the server sends notifications, as `GET /api/v1/admin/notifications` lists it. [kind] is `webhook`, `ntfy` or
 * `apprise`; [events] the event types it takes; [hasSecret] says whether a signing key or access token is set, which
 * the API never returns. [lastDelivery] is the newest attempt, null before the first.
 */
@Serializable data class NotificationTarget(
    val id: String,
    val name: String,
    val kind: String,
    val url: String,
    val hasSecret: Boolean,
    val events: List<String>,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val lastDelivery: NotificationDelivery? = null,
)

/**
 * One delivery of [event] to a target: [ok] when the target answered 2xx; [status] its last HTTP status, null when it
 * never answered; [error] why the last attempt failed; [attempts] how many it took.
 */
@Serializable data class NotificationDelivery(
    val event: String,
    val at: String,
    val ok: Boolean,
    val status: Int? = null,
    val error: String? = null,
    val attempts: Int = 1,
)

/**
 * The body of `POST /api/v1/admin/notifications`, and of `PATCH`, where an absent field keeps its value. An empty
 * [secret] clears it.
 */
@Serializable data class SaveNotificationTarget(
    val name: String? = null,
    val kind: String? = null,
    val url: String? = null,
    val secret: String? = null,
    val events: List<String>? = null,
    val enabled: Boolean? = null,
)
