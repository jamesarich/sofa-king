package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.NotificationDelivery
import dev.jamesrich.sofaking.backend.api.NotificationTarget
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationsTest : AdminPortalCase() {
    /** A port nothing listens on, so a delivery fails at once and nothing leaves the machine. */
    private val nowhere = "http://127.0.0.1:1/hook"

    @Test fun `the admin API adds, changes, tests and removes a target, keeping its secret`() = testApplication {
        portal {
            val base = "/api/v1/admin/notifications"
            assertEquals(HttpStatusCode.Unauthorized, client.get(base) { bearerAuth(token) }.status)
            val created = client.post(base) {
                bearerAuth(admin)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Hooks","kind":"webhook","url":"$nowhere","secret":"hush-hush"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
            assertFalse(created.bodyAsText().contains("hush-hush"))
            val target = json.decodeFromString<NotificationTarget>(created.bodyAsText())
            assertTrue(target.hasSecret && target.enabled)
            val refused = client.post(base) {
                bearerAuth(admin)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Bad","kind":"webhook","url":"file:///etc/passwd"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, refused.status)

            val changed = client.patch("$base/${target.id}") {
                bearerAuth(admin)
                contentType(ContentType.Application.Json)
                setBody("""{"events":["disk.low"],"enabled":false}""")
            }
            assertEquals(HttpStatusCode.OK, changed.status)
            val after = json.decodeFromString<NotificationTarget>(changed.bodyAsText())
            assertEquals(listOf("disk.low"), after.events)
            assertFalse(after.enabled)
            assertTrue(after.hasSecret, "a change without a secret keeps it")

            val test = client.post("$base/${target.id}/test") { bearerAuth(admin) }
            assertEquals(HttpStatusCode.OK, test.status)
            assertFalse(json.decodeFromString<NotificationDelivery>(test.bodyAsText()).ok)
            val deliveries = client.get("$base/${target.id}/deliveries") { bearerAuth(admin) }.bodyAsText()
            assertEquals("test", json.decodeFromString<List<NotificationDelivery>>(deliveries).single().event)
            val listed = client.get(base) { bearerAuth(admin) }.bodyAsText()
            assertEquals(false, json.decodeFromString<List<NotificationTarget>>(listed).single().lastDelivery?.ok)

            assertEquals(HttpStatusCode.NoContent, client.delete("$base/${target.id}") { bearerAuth(admin) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("$base/${target.id}") { bearerAuth(admin) }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/${target.id}") { bearerAuth(admin) }.status)
        }
    }

    @Test fun `the Connect page adds a target, tests it, turns it off and removes it`() = testApplication {
        portal {
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/connect").status)
            browser.signIn()
            val empty = browser.get("/admin/connect").bodyAsText()
            assertTrue(empty.contains("None yet"))
            assertTrue(empty.contains("aria-current=\"page\">Connect<"), "the tab is marked")
            assertTrue(browser.get("/admin/settings").bodyAsText().contains("href=\"/admin/connect\""))
            val csrf = field(empty, "csrf")
            assertEquals(
                HttpStatusCode.Forbidden,
                browser.post("/admin/connect/targets", "name" to "x", "kind" to "ntfy", "url" to nowhere).status,
            )
            val added = browser.post(
                "/admin/connect/targets",
                "csrf" to csrf,
                "name" to "Phone",
                "kind" to "ntfy",
                "url" to nowhere,
                "secret" to "tk_secret",
                "event" to "disk.low",
                "event" to "recording.failed",
            )
            assertEquals(HttpStatusCode.SeeOther, added.status)
            val target = runtime.notifications!!.list().single()
            assertEquals(listOf("recording.failed", "disk.low"), target.events)
            val page = browser.get("/admin/connect").bodyAsText()
            assertTrue(page.contains("Added Phone"))
            assertFalse(page.contains("tk_secret"), "the secret is never shown")

            browser.post("/admin/connect/targets/${target.id}/test", "csrf" to csrf)
            assertTrue(browser.get("/admin/connect").bodyAsText().contains("The test failed"))
            browser.post("/admin/connect/targets/${target.id}/enabled", "csrf" to csrf, "enabled" to "0")
            assertFalse(runtime.notifications!!.get(target.id).enabled)
            browser.post(
                "/admin/connect/targets/${target.id}",
                "csrf" to csrf,
                "name" to "Phone",
                "kind" to "ntfy",
                "url" to nowhere,
                "secret" to "",
                "event" to "disk.low",
            )
            val saved = runtime.notifications!!.get(target.id)
            assertEquals(listOf("disk.low"), saved.events)
            assertTrue(saved.hasSecret, "a blank secret keeps the one set")
            val asked = browser.post("/admin/connect/targets/${target.id}/delete", "csrf" to csrf)
            assertTrue(asked.bodyAsText().contains("Remove Phone?"))
            assertEquals(1, runtime.notifications!!.list().size)
            browser.post("/admin/connect/targets/${target.id}/delete", "csrf" to csrf, "confirm" to "yes")
            assertTrue(runtime.notifications!!.list().isEmpty())
        }
    }
}
