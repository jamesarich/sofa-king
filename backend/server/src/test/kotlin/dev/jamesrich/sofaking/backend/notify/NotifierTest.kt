package dev.jamesrich.sofaking.backend.notify

import dev.jamesrich.sofaking.backend.api.SaveNotificationTarget
import dev.jamesrich.sofaking.backend.api.ServerEvent
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.backend.pollFor
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.notificationDeliveries
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotifierTest {
    private class Sent(val request: HttpRequestData, val body: String)

    private val identity = NotifyIdentity("Sofa King", "server-1", "1.2.3")

    @Test fun `a webhook gets the envelope, signed with its secret`() = case { _, service, sent ->
        val target = service.create(
            SaveNotificationTarget("Hooks", NotifyKind.WEBHOOK, "http://192.0.2.10/hook", "s3cret"),
        )
        assertTrue(target.hasSecret)
        val delivery = service.test(target.id)
        assertTrue(delivery.ok)
        assertEquals(200, delivery.status)
        val request = sent.single()
        assertEquals(NotifyEvent.TEST, request.request.headers[EVENT_HEADER])
        assertEquals(signature("s3cret", request.body), request.request.headers[SIGNATURE_HEADER])
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("test", body["eventType"]!!.jsonPrimitive.content)
        assertEquals("server-1", body["serverId"]!!.jsonPrimitive.content)
        assertEquals("Sofa King", body["instanceName"]!!.jsonPrimitive.content)
        assertEquals("1.2.3", body["version"]!!.jsonPrimitive.content)
        assertFalse(service.list().single().toString().contains("s3cret"), "the API never returns the secret")
        assertEquals(delivery, service.list().single().lastDelivery)
    }

    @Test fun `the signature is the hex HMAC-SHA256 of the body`() {
        // RFC 4231 test case 2.
        assertEquals(
            "sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            signature("Jefe", "what do ya want for nothing?"),
        )
    }

    @Test fun `ntfy gets the title, tags and priority as parameters and the secret as a bearer token`() =
        case { _, service, sent ->
            val target = service.create(
                SaveNotificationTarget("Phone", NotifyKind.NTFY, "https://ntfy.example/sofa", "tk_abc"),
            )
            service.test(target.id)
            val request = sent.single().request
            assertEquals("Sofa King test", request.url.parameters["title"])
            assertEquals("information_source", request.url.parameters["tags"])
            assertEquals("default", request.url.parameters["priority"])
            assertEquals("Bearer tk_abc", request.headers[HttpHeaders.Authorization])
            assertEquals("Notifications reach Phone.", sent.single().body)
        }

    @Test fun `apprise gets its title, body and type`() = case { _, service, sent ->
        val target = service.create(SaveNotificationTarget("Apprise", NotifyKind.APPRISE, "http://192.0.2.11/notify"))
        service.test(target.id)
        val body = Json.parseToJsonElement(sent.single().body).jsonObject
        assertEquals("Sofa King test", body["title"]!!.jsonPrimitive.content)
        assertEquals("info", body["type"]!!.jsonPrimitive.content)
    }

    @Test fun `a failing target is tried three times, and a refusal once`() = runBlocking {
        val answers = mutableListOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.BadGateway, HttpStatusCode.OK)
        withNotifier({ respond("", answers.removeFirstOrNull() ?: HttpStatusCode.NotFound) }) { store, notifier, _ ->
            val service = NotificationTargetsService(store, MutableClock(), notifier)
            val flaky = service.create(
                SaveNotificationTarget("Flaky", NotifyKind.WEBHOOK, "http://192.0.2.10/a", events = listOf("disk.low")),
            )
            notifier.offer(listOf(disk()))
            val delivery = pollFor(5_000) { store.notificationDeliveries(flaky.id).firstOrNull() }
            assertTrue(delivery.ok)
            assertEquals(3, delivery.attempts)

            service.update(flaky.id, SaveNotificationTarget(enabled = false))
            val refusing = service.create(
                SaveNotificationTarget("Gone", NotifyKind.WEBHOOK, "http://192.0.2.10/b", events = listOf("disk.low")),
            )
            notifier.offer(listOf(disk()))
            val refused = pollFor(5_000) { store.notificationDeliveries(refusing.id).firstOrNull() }
            assertFalse(refused.ok)
            assertEquals(1, refused.attempts, "a 404 is not tried again")
            assertEquals("HTTP 404", refused.error)
            assertEquals(1, store.notificationDeliveries(flaky.id).size, "a target turned off gets nothing")
        }
    }

    @Test fun `a target gets only the events it chose`() = runBlocking {
        withNotifier({ respond("", HttpStatusCode.OK) }) { store, notifier, sent ->
            val service = NotificationTargetsService(store, MutableClock(), notifier)
            val target = service.create(
                SaveNotificationTarget("Disk", NotifyKind.WEBHOOK, "http://192.0.2.10/", events = listOf("disk.low")),
            )
            notifier.offer(notificationsOf(recording(Wire.RecordingState.COMPLETED)) + disk())
            pollFor(5_000) { store.notificationDeliveries(target.id).firstOrNull() }
            assertEquals(listOf("disk.low"), sent.map { it.request.headers[EVENT_HEADER] })
        }
    }

    @Test fun `targets are checked before they are kept`() = case { _, service, _ ->
        val bad = listOf(
            SaveNotificationTarget("", NotifyKind.WEBHOOK, "http://192.0.2.10/"),
            SaveNotificationTarget("x", "email", "http://192.0.2.10/"),
            SaveNotificationTarget("x", NotifyKind.WEBHOOK, "ftp://192.0.2.10/"),
            SaveNotificationTarget("x", NotifyKind.WEBHOOK, "http://user:pass@192.0.2.10/"),
            SaveNotificationTarget("x", NotifyKind.WEBHOOK, "http://192.0.2.10/", events = listOf("nope")),
        )
        for (body in bad) {
            val failure = assertFailsWith<ApiFailure>(body.toString()) { service.create(body) }
            assertEquals(HttpStatusCode.BadRequest, failure.status)
        }
        val local = service.create(SaveNotificationTarget("LAN", NotifyKind.NTFY, "http://127.0.0.1:8080/topic"))
        assertTrue(NotifyEvent.ATTENTION_RAISED !in local.events, "attention items are left out by default")
        assertTrue(NotifyEvent.RECORDING_FAILED in local.events)
        val cleared = service.update(local.id, SaveNotificationTarget(secret = ""))
        assertFalse(cleared.hasSecret)
        service.delete(local.id)
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiFailure> { service.get(local.id) }.status)
    }

    @Test fun `server events read as notifications`() {
        val finished = notificationsOf(recording(Wire.RecordingState.COMPLETED)).single()
        assertEquals(NotifyEvent.RECORDING_FINISHED, finished.event)
        assertEquals("Movie · Part one on Channel", finished.message)
        assertEquals(NotifyTone.SUCCESS, finished.tone)
        val failed = notificationsOf(recording(Wire.RecordingState.FAILED, "tuner lost")).single()
        assertEquals(NotifyEvent.RECORDING_FAILED, failed.event)
        assertTrue(failed.message.endsWith("(tuner lost)"))
        val scheduled = notificationsOf(recording(Wire.RecordingState.SCHEDULED)).single()
        assertEquals(NotifyEvent.RECORDING_SCHEDULED, scheduled.event)
        assertTrue(notificationsOf(recording("canceled")).isEmpty())
        val library = notificationsOf(
            event(
                EventType.LIBRARY_ADDED,
                buildJsonObject {
                    put("library", "Movies")
                    put("added", 12)
                    put("titles", Json.parseToJsonElement("""["Big Buck Bunny (2008)"]"""))
                },
            ),
        ).single()
        assertEquals("New in Movies", library.title)
        assertEquals("Big Buck Bunny (2008), and 11 more", library.message)
        assertTrue(notificationsOf(event(EventType.SESSION_STARTED, buildJsonObject {})).isEmpty())
        assertEquals("1.5 GiB free, under the 10 GiB floor", disk().message)
    }

    private fun recording(state: String, reason: String? = null) = event(
        EventType.RECORDING_STATE,
        buildJsonObject {
            put("id", "r1")
            put("title", "Movie")
            put("episodeTitle", "Part one")
            put("channelName", "Channel")
            put("state", state)
            put("reason", reason)
        },
    )

    private fun disk() = notificationsOf(
        event(
            EventType.DISK_LOW,
            buildJsonObject {
                put("freeGib", "1.5")
                put("floorGib", 10)
            },
        ),
    ).single()

    private fun event(type: String, data: JsonObject) = ServerEvent(type, "2026-10-10T13:00:00Z", data)

    private fun case(block: suspend (BackendStore, NotificationTargetsService, List<Sent>) -> Unit) = runBlocking {
        withNotifier({ respond("", HttpStatusCode.OK) }) { store, notifier, sent ->
            block(store, NotificationTargetsService(store, MutableClock(), notifier), sent)
        }
    }

    private suspend fun withNotifier(
        answer: suspend MockRequestHandleScope.() -> HttpResponseData,
        block: suspend (BackendStore, Notifier, List<Sent>) -> Unit,
    ) {
        val dir = Files.createTempDirectory("sofa-notify-")
        val sent = Collections.synchronizedList(mutableListOf<Sent>())
        val http = HttpClient(
            MockEngine { request ->
                val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                sent += Sent(request, body)
                answer()
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            openStore(dir).use { store ->
                val notifier = Notifier(store, http, MutableClock(), { identity }, retryDelays = listOf(1, 1))
                notifier.start(scope, emptyFlow())
                block(store, notifier, sent)
            }
        } finally {
            scope.cancel()
            http.close()
            dir.toFile().deleteRecursively()
        }
    }
}
