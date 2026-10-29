package dev.jamesrich.sofaking.backend.notify

import dev.jamesrich.sofaking.backend.api.NotificationDelivery
import dev.jamesrich.sofaking.backend.api.NotificationTarget
import dev.jamesrich.sofaking.backend.api.SaveNotificationTarget
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.StoredNotificationTarget
import dev.jamesrich.sofaking.backend.store.deleteNotificationTarget
import dev.jamesrich.sofaking.backend.store.lastNotificationDeliveries
import dev.jamesrich.sofaking.backend.store.notificationDeliveries
import dev.jamesrich.sofaking.backend.store.notificationTargets
import dev.jamesrich.sofaking.backend.store.saveNotificationTarget
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.util.UUID

private const val MAX_NAME = 80
private const val MAX_URL = 2048
private const val MAX_SECRET = 512

/** What a new target takes when it names no events: all but the attention items, which Home already shows. */
private val DEFAULT_EVENTS = NotifyEvent.ALL - setOf(NotifyEvent.ATTENTION_RAISED, NotifyEvent.ATTENTION_CLEARED)

/** The notification targets: `/api/v1/admin/notifications` and the Connect page add, change, test and remove them. */
class NotificationTargetsService(
    private val store: BackendStore,
    private val clock: Clock,
    private val notifier: Notifier,
) {
    /** Every target with its newest delivery, the secrets left out and the URL masked to its host. */
    suspend fun list(): List<NotificationTarget> {
        val last = store.lastNotificationDeliveries()
        return store.notificationTargets().map { it.toWire(last[it.id]) }
    }

    suspend fun get(id: String): NotificationTarget = stored(id).toWire(store.lastNotificationDeliveries()[id])

    suspend fun create(body: SaveNotificationTarget): NotificationTarget {
        val now = clock.instant().toString()
        val target = StoredNotificationTarget(
            UUID.randomUUID().toString(),
            name(body.name ?: invalid("name is required")),
            kind(body.kind ?: invalid("kind is required")),
            url(body.url ?: invalid("url is required")),
            secret(body.secret),
            events(body.events ?: DEFAULT_EVENTS.toList()),
            body.enabled ?: true,
            now,
            now,
        )
        store.saveNotificationTarget(target)
        return target.toWire(null)
    }

    /**
     * Changes the fields [body] gives; an empty secret clears it. An empty URL, or the masked one a listing shows,
     * keeps the stored URL.
     */
    suspend fun update(id: String, body: SaveNotificationTarget): NotificationTarget {
        val before = stored(id)
        val after = before.copy(
            name = body.name?.let(::name) ?: before.name,
            kind = body.kind?.let(::kind) ?: before.kind,
            url = body.url?.takeIf { it.isNotBlank() && it.trim() != maskedUrl(before.url) }?.let(::url) ?: before.url,
            secret = if (body.secret == null) before.secret else secret(body.secret),
            events = body.events?.let(::events) ?: before.events,
            enabled = body.enabled ?: before.enabled,
            updatedAt = clock.instant().toString(),
        )
        store.saveNotificationTarget(after)
        return after.toWire(store.lastNotificationDeliveries()[id])
    }

    suspend fun delete(id: String) {
        if (!store.deleteNotificationTarget(id)) throw notFound()
    }

    /** Sends the target a test notification now and returns how it went. */
    suspend fun test(id: String): NotificationDelivery = notifier.test(stored(id))

    suspend fun deliveries(id: String): List<NotificationDelivery> {
        stored(id)
        return store.notificationDeliveries(id)
    }

    private suspend fun stored(id: String) = store.notificationTargets().firstOrNull { it.id == id } ?: throw notFound()
}

private fun StoredNotificationTarget.toWire(last: NotificationDelivery?) = NotificationTarget(
    id, name, kind, maskedUrl(url), secret != null, events, enabled, createdAt, updatedAt, last,
)

/** The scheme and host of [url] with `/…` for the rest: a webhook's or ntfy topic's path is the credential. */
internal fun maskedUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull()
    val host = uri?.host ?: return "…"
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return "${uri.scheme.lowercase()}://$host$port/…"
}

private fun name(value: String): String {
    val name = value.trim().replace(Regex("\\s+"), " ")
    if (name.isEmpty() || name.length > MAX_NAME) invalid("name must be 1 to $MAX_NAME characters")
    return name
}

private fun kind(value: String): String =
    value.takeIf { it in NotifyKind.ALL } ?: invalid("kind must be webhook, ntfy or apprise")

/** An absolute `http` or `https` URL with a host; LAN and loopback addresses are allowed. */
private fun url(value: String): String {
    val url = value.trim()
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        null
    }
    val valid = uri != null && url.length <= MAX_URL && uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrEmpty() && uri.userInfo == null
    return url.takeIf { valid } ?: invalid("url must be an http or https URL with a host, without a user name")
}

private fun secret(value: String?): String? {
    if (value != null && value.length > MAX_SECRET) invalid("secret must be at most $MAX_SECRET characters")
    return value?.takeIf { it.isNotEmpty() }
}

private fun events(values: List<String>): List<String> {
    val unknown = values.filter { it !in NotifyEvent.ALL }
    if (unknown.isNotEmpty()) invalid("unknown events: ${unknown.joinToString().take(MAX_NAME)}")
    // Kept in the order the page lists them, once each.
    return NotifyEvent.LABELS.keys.filter { it in values }
}

private fun invalid(message: String): Nothing = throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", message)

private fun notFound() =
    ApiFailure(HttpStatusCode.NotFound, "NOTIFICATION_TARGET_NOT_FOUND", "No such notification target")
