package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.main
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.content
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.path
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Policy edits from the portal: the CSRF gate, validation, stale revisions and Apply now. */
class AdminPortalPolicyTest : AdminPortalCase() {
    @Test fun `apply now runs the policy behind the session and the CSRF token`() = testApplication {
        portal(basePolicy) {
            assertEquals(HttpStatusCode.Unauthorized, browser.post("/admin/policy/apply").status)
            browser.signIn()
            val html = browser.get("/admin/settings").bodyAsText()
            assertEquals(HttpStatusCode.Forbidden, browser.post("/admin/policy/apply").status)
            assertNull(runtime.policy!!.lastApplied(), "a refused apply runs nothing")
            val applied = browser.post("/admin/policy/apply", "csrf" to field(html, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, applied.status)
            assertEquals("/admin/settings?advanced=1#policy", applied.headers[HttpHeaders.Location])
            assertNotNull(runtime.policy.lastApplied())
            assertTrue(browser.get("/admin/settings").bodyAsText().contains("Applied the policy:"))
        }
    }

    @Test fun `apply now without a policy says so`() = testApplication {
        portal {
            browser.signIn()
            val html = browser.get("/admin/settings").bodyAsText()
            assertTrue(html.contains("No lineup policy"))
            val response = browser.post("/admin/policy/apply", "csrf" to field(html, "csrf"))
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("No lineup policy is configured."))
        }
    }

    @Test fun `policy edits need the session and the CSRF token`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/groups").status)
            for (path in listOf("/admin/groups/edit", "/admin/providers/edit")) {
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    browser.post(
                        path,
                        "op" to "block",
                    ).status,
                    path,
                )
            }
            browser.signIn()
            val before = Files.readString(policyFile!!)
            val page = browser.get("/admin/groups").bodyAsText()
            for (path in listOf("/admin/groups/edit", "/admin/providers/edit")) {
                val refused = browser.post(
                    path,
                    "op" to "block",
                    "group" to "News",
                    "start" to "500",
                    "revision" to field(page, "revision"),
                    "confirm" to "yes",
                )
                assertEquals(HttpStatusCode.Forbidden, refused.status, path)
                val notAForm = browser.post(path) {
                    contentType(ContentType.Text.Plain)
                    setBody("op=block")
                }
                assertEquals(
                    HttpStatusCode.Forbidden,
                    notAForm.status,
                    "a body that is not a form is refused, not an error: $path",
                )
            }
            val signIn = browser.post("/admin/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$admin"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, signIn.status, "sign-in takes only a form")
            assertEquals(before, Files.readString(policyFile), "a refused edit writes nothing")
        }
    }

    @Test fun `accepting a merge folds the merged-away key's override into the kept one`() = testApplication {
        val stations =
            """{"numberonly":{"number":"870","required":false,"sources":[]},"keptnumber":{"number":"1976"},""" +
                """"named":{"name":"Renamed"},"plain":{"number":"40"},"both":{"name":"One"},"other":""" +
                """{"group":"Sports"}}"""
        portal(basePolicy.dropLast(1) + ""","stations":$stations}""") {
            browser.signIn()
            suspend fun accept(from: String, to: String): HttpResponse {
                val page = browser.get("/admin/groups").bodyAsText()
                return browser.post("/admin/merges/accept", "from" to from, "to" to to, "csrf" to field(page, "csrf"))
            }
            assertEquals(HttpStatusCode.SeeOther, accept("numberonly", "keptnumber").status)
            var saved = stationsIn(policyFile!!)
            assertNull(saved["numberonly"], "an override holding only a number goes with its key")
            assertEquals("1976", saved["keptnumber"]!!.jsonObject["number"]!!.jsonPrimitive.content)
            assertEquals(
                "keptnumber",
                policyJson(
                    policyFile,
                )["names"]!!.jsonObject["aliases"]!!.jsonObject["numberonly"]!!.jsonPrimitive.content,
            )

            assertEquals(HttpStatusCode.SeeOther, accept("named", "plain").status)
            saved = stationsIn(policyFile)
            assertNull(saved["named"])
            assertEquals(
                "Renamed",
                saved["plain"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                "other settings move to the kept key",
            )
            assertEquals(
                "40",
                saved["plain"]!!.jsonObject["number"]!!.jsonPrimitive.content,
                "the kept key's number stays",
            )

            val before = Files.readString(policyFile)
            val refused = accept("both", "other")
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertTrue(refused.bodyAsText().contains("both have station settings"), "the reason shows inline")
            assertEquals(before, Files.readString(policyFile), "nothing is written")
        }
    }

    @Test fun `a number block is written into the policy and applied`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin/groups").bodyAsText()
            assertTrue(page.contains("Groups and number blocks") && page.contains("Category map"))
            val saved = browser.post(
                "/admin/groups/edit",
                "op" to "block",
                "group" to "Sports",
                "start" to "500",
                "csrf" to field(page, "csrf"),
                "revision" to field(page, "revision"),
                "confirm" to "yes",
            )
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals("/admin/groups#blocks", saved.headers[HttpHeaders.Location])
            assertEquals(
                500,
                policyJson(policyFile!!)["numbering"]!!.jsonObject["groups"]!!.jsonObject["Sports"]!!.jsonPrimitive.int,
            )
            assertEquals(
                Files.readString(policyFile).let {
                    java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b ->
                        "%02x".format(b)
                    }
                },
                runtime.policy.document().revision,
            )
            val after = browser.get("/admin/groups").bodyAsText()
            assertTrue(after.contains("Saved: number Sports from 500."), "the save is confirmed")
            assertTrue(after.contains(">Sports<"), "the block is listed")
        }
    }

    @Test fun `an invalid edit shows every policy error inline and writes nothing`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val before = Files.readString(policyFile!!)
            val page = browser.get("/admin/groups").bodyAsText()
            val response = browser.post(
                "/admin/groups/edit",
                "op" to "pattern",
                "name" to "(",
                "group" to "News",
                "csrf" to field(page, "csrf"),
                "revision" to field(page, "revision"),
                "confirm" to "yes",
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("The policy would not be valid") &&
                    body.contains("groups.patterns[0].name: invalid pattern"),
                body,
            )
            assertEquals(before, Files.readString(policyFile))

            val unknown = browser.post(
                "/admin/providers/edit",
                "op" to "add",
                "provider" to "nope",
                "csrf" to field(page, "csrf"),
                "revision" to field(page, "revision"),
                "confirm" to "yes",
            )
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertTrue(unknown.bodyAsText().contains("unknown provider nope"))
            assertEquals(before, Files.readString(policyFile))
        }
    }

    @Test
    fun `an edit against a stale revision is held for confirmation against the current policy`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val before = Files.readString(policyFile!!)
            val page = browser.get("/admin/groups").bodyAsText()
            val stale = "0".repeat(64)
            val held = browser.post(
                "/admin/groups/edit",
                "op" to "weak",
                "category" to "Featured",
                "csrf" to field(page, "csrf"),
                "revision" to stale,
                "confirm" to "yes",
            )
            assertEquals(HttpStatusCode.Conflict, held.status)
            val body = held.bodyAsText()
            assertTrue(body.contains("The policy changed since this page loaded"), body)
            assertEquals(before, Files.readString(policyFile), "nothing is saved on a stale revision")
            val current = runtime.policy.document().revision
            assertTrue(
                body.contains("name=\"revision\" value=\"$current\""),
                "the confirm step carries the current revision",
            )
            val saved = browser.post(
                "/admin/groups/edit",
                "op" to "weak",
                "category" to "Featured",
                "csrf" to field(page, "csrf"),
                "revision" to current,
                "confirm" to "yes",
            )
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals(
                Json.parseToJsonElement("""["featured"]"""),
                policyJson(policyFile)["groups"]!!.jsonObject["weak"],
            )
        }
    }

    @Test fun `hiding a provider entry shows what turns off and saves only once confirmed`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin/providers").bodyAsText()
            assertTrue(page.contains("Priority"))
            val ts = runtime.lineup.entries().single { it.name == "TS Station" }
            assertTrue(ts.enabled)
            val fields = arrayOf(
                "op" to "enabled",
                "index" to "3",
                "enabled" to "false",
                "csrf" to field(page, "csrf"),
                "revision" to field(page, "revision"),
            )
            val asked = browser.post("/admin/providers/edit", *fields)
            assertEquals(HttpStatusCode.OK, asked.status)
            val dialog = asked.bodyAsText()
            assertTrue(
                dialog.contains("role=\"dialog\"") && dialog.contains("turn off") && dialog.contains("TS Station") &&
                    dialog.contains("Save and apply"),
                dialog,
            )
            assertNull(
                policyJson(policyFile!!)["providers"]!!.jsonArray[3].jsonObject["enabled"],
                "nothing is saved before the confirm",
            )
            val saved = browser.post("/admin/providers/edit", *fields, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals(
                false,
                policyJson(policyFile)["providers"]!!.jsonArray[3].jsonObject["enabled"]!!.jsonPrimitive.boolean,
            )
            assertFalse(runtime.lineup.entry(ts.id)!!.enabled, "the apply turned the station off")

            val moved = browser.post(
                "/admin/providers/edit",
                "op" to "move",
                "index" to "1",
                "to" to "up",
                "csrf" to field(page, "csrf"),
                "revision" to runtime.policy.document().revision,
                "confirm" to "yes",
            )
            assertEquals(HttpStatusCode.SeeOther, moved.status, moved.bodyAsText())
            assertEquals(
                listOf("alt", "fixture", "twin", "ts"),
                policyJson(policyFile)["providers"]!!.jsonArray.map {
                    it.jsonObject["id"]!!.jsonPrimitive.content
                },
            )
        }
    }

    @Test fun `an attention item can be ignored until its text changes, and shown again`() = testApplication {
        portal(basePolicy, guides = { listOf(it.brokenGuide) }) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin").bodyAsText()
            val csrf = field(page, "csrf")
            val fingerprint = Regex(
                """name="key" value="guides-failed"><input type="hidden" name="fingerprint" value="([0-9a-f]{16})"""",
            ).find(page)?.groupValues?.get(1)
            assertNotNull(fingerprint, page)
            assertTrue(page.contains("Ignore until it changes: Guide broken has never imported"))

            val ignored = browser.post(
                "/admin/attention/ignore",
                "key" to "guides-failed",
                "fingerprint" to fingerprint,
                "csrf" to csrf,
            )
            assertEquals(HttpStatusCode.SeeOther, ignored.status, ignored.bodyAsText())
            val after = browser.get("/admin").bodyAsText()
            assertTrue(after.contains("Ignored until it changes (1)"), after)
            assertTrue(
                after.contains("Show again: Guide broken has never imported"),
                "the item sits under the ignored list",
            )
            assertFalse(after.contains("Ignore until it changes: Guide broken"), "and not in the main list")

            val shown = browser.post("/admin/attention/show", "key" to "guides-failed", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, shown.status)
            assertTrue(
                browser.get("/admin").bodyAsText().contains("Ignore until it changes: Guide broken has never imported"),
            )
        }
    }
}
