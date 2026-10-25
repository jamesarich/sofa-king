package dev.jamesrich.sofaking.backend.notify

import dev.jamesrich.sofaking.backend.api.NotificationDelivery
import dev.jamesrich.sofaking.backend.api.ServerEvent
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.StoredNotificationTarget
import dev.jamesrich.sofaking.backend.store.addNotificationDelivery
import dev.jamesrich.sofaking.backend.store.notificationTargets
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Clock

private val log = LoggerFactory.getLogger(Notifier::class.java)

/** How many deliveries may wait; past it a new one is dropped and logged, never holding up the server. */
private const val QUEUE_SIZE = 256

/** How many deliveries go out at once, so one slow target does not hold up the rest. */
private const val SENDERS = 4

/** The waits before the second and third attempts. */
private val RETRY_DELAYS_MS = listOf(2_000L, 10_000L)

private const val MAX_ERROR = 200

/**
 * Sends the server's events to the notification targets that take them: a bounded queue, a few senders, three
 * attempts with a pause between, and each outcome kept as the target's delivery. A target answers on the address the
 * admin gave it, LAN and loopback included: unlike a provider's, it is the admin's own choice.
 */
class Notifier(
    private val store: BackendStore,
    private val http: HttpClient,
    private val clock: Clock,
    /** Who sends, read once at the first delivery: the server id comes from the store. */
    private val identity: suspend () -> NotifyIdentity,
    private val retryDelays: List<Long> = RETRY_DELAYS_MS,
) {
    private class Pending(val target: StoredNotificationTarget, val notification: Notification)

    private val queue = Channel<Pending>(QUEUE_SIZE)
    private var sender: NotifyIdentity? = null

    /** Reads [events] and sends their notifications from [scope] until it ends. */
    fun start(scope: CoroutineScope, events: Flow<ServerEvent>) {
        scope.launch(CoroutineName("notify-events")) {
            events.collect { event ->
                val notifications = notificationsOf(event)
                if (notifications.isNotEmpty()) runCatchingCancellable { offer(notifications) }
            }
        }
        repeat(SENDERS) { n ->
            scope.launch(CoroutineName("notify-send-$n")) {
                for (pending in queue) deliver(pending.target, pending.notification, retryDelays)
            }
        }
    }

    /** Queues each of [notifications] for the enabled targets that take its event. */
    suspend fun offer(notifications: List<Notification>) {
        val targets = store.notificationTargets().filter { it.enabled }
        for (notification in notifications) {
            for (target in targets.filter { notification.event in it.events }) {
                if (queue.trySend(Pending(target, notification)).isFailure) {
                    log.warn("notification dropped target={} event={} reason=queue-full", target.id, notification.event)
                }
            }
        }
    }

    /** Sends a test notification to [target] now, once, and returns how it went. */
    suspend fun test(target: StoredNotificationTarget): NotificationDelivery = deliver(
        target,
        Notification(
            NotifyEvent.TEST,
            clock.instant().toString(),
            "Sofa King test",
            "Notifications reach ${target.name}.",
            NotifyTone.INFO,
            JsonObject(emptyMap()),
        ),
        retries = emptyList(),
    )

    /**
     * Sends [notification] to [target], trying again after each of [retries] while it fails, and records the outcome.
     * A 4xx other than 408 and 429 is the target refusing it, so it is not tried again.
     */
    private suspend fun deliver(
        target: StoredNotificationTarget,
        notification: Notification,
        retries: List<Long>,
    ): NotificationDelivery {
        val from = sender ?: identity().also { sender = it }
        var attempts = 0
        var status: Int? = null
        var error: String? = null
        while (true) {
            attempts++
            val outcome = runCatchingCancellable { http.send(target, notification, from) }
            status = outcome.getOrNull()
            error = when (status) {
                null -> outcome.exceptionOrNull()?.javaClass?.simpleName ?: "no answer"
                in OK -> null
                else -> "HTTP $status"
            }
            val final = error == null || (status != null && status in REFUSED) || attempts > retries.size
            if (final) break
            delay(retries[attempts - 1])
        }
        val delivery = NotificationDelivery(
            notification.event,
            clock.instant().toString(),
            error == null,
            status,
            error?.take(MAX_ERROR),
            attempts,
        )
        // The URL can carry a topic or a key, so the log names the target by id alone.
        if (error == null) {
            log.info("notification sent target={} event={} attempts={}", target.id, notification.event, attempts)
        } else {
            log.warn(
                "notification failed target={} event={} attempts={} error={}",
                target.id,
                notification.event,
                attempts,
                error,
            )
        }
        runCatchingCancellable { store.addNotificationDelivery(target.id, delivery) }
            .onFailure { log.warn("notification delivery not saved class={}", it.javaClass.simpleName) }
        return delivery
    }

    private companion object {
        val OK = 200..299
        val REFUSED = (400..499) - setOf(408, 429)
    }
}
