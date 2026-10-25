package dev.jamesrich.sofaking.backend.notify

import dev.jamesrich.sofaking.backend.store.StoredNotificationTarget
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The kinds of notification target. */
object NotifyKind {
    const val WEBHOOK = "webhook"
    const val NTFY = "ntfy"
    const val APPRISE = "apprise"

    val ALL = setOf(WEBHOOK, NTFY, APPRISE)
}

/** The header a webhook's signature travels in: `sha256=` and the HMAC-SHA256 of the body, in hex, under the secret. */
const val SIGNATURE_HEADER = "X-SofaKing-Signature"

/** The header naming a webhook's event, so a receiver can route before it reads the body. */
const val EVENT_HEADER = "X-SofaKing-Event"

/** Who sends: the webhook's `instanceName`, `serverId` and `version`. */
data class NotifyIdentity(val instanceName: String, val serverId: String, val version: String)

/**
 * Sends [notification] to [target] once and returns the HTTP status it answered. A webhook gets the JSON envelope,
 * signed when the target has a secret; ntfy gets the message, its title, tags and priority as query parameters, and
 * the secret as a bearer token; Apprise gets its API's title, body and type.
 */
internal suspend fun HttpClient.send(
    target: StoredNotificationTarget,
    notification: Notification,
    identity: NotifyIdentity,
): Int = when (target.kind) {
    // Query parameters rather than headers, so a title outside ASCII arrives intact.
    NotifyKind.NTFY -> post(target.url) {
        url {
            parameters.append("title", notification.title)
            parameters.append("tags", notification.tone.ntfyTag)
            parameters.append("priority", if (notification.tone == NotifyTone.FAILURE) "high" else "default")
        }
        target.secret?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        setBody(notification.message)
    }.status.value

    NotifyKind.APPRISE -> post(target.url) {
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("title", notification.title)
                put("body", notification.message)
                put("type", notification.tone.apprise)
            }.toString(),
        )
    }.status.value

    else -> {
        val body = webhookBody(notification, identity)
        post(target.url) {
            contentType(ContentType.Application.Json)
            header(EVENT_HEADER, notification.event)
            target.secret?.let { header(SIGNATURE_HEADER, signature(it, body)) }
            setBody(body)
        }.status.value
    }
}

/** The webhook's JSON envelope for [notification]. */
internal fun webhookBody(notification: Notification, identity: NotifyIdentity): String = buildJsonObject {
    put("eventType", notification.event)
    put("instanceName", identity.instanceName)
    put("serverId", identity.serverId)
    put("version", identity.version)
    put("occurredAt", notification.occurredAt)
    put("title", notification.title)
    put("message", notification.message)
    notification.path?.let { put("path", it) }
    put("data", notification.data)
}.toString()

/** `sha256=` and the hex HMAC-SHA256 of [body] under [secret]. */
internal fun signature(secret: String, body: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
}
