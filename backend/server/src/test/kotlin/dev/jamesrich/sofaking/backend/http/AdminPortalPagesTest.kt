package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.admin.spacedCode
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.playback.AdCensus
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.recordAdChannel
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.content
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.date
import io.ktor.http.path
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The ads, watching now, API, viewers, library and recordings pages. */
class AdminPortalPagesTest : AdminPortalCase() {
    @Test fun `the ads page lists each channel's census by its lineup name`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            val channel = runtime.lineup.entries().first()
            runtime.store.recordAdChannel(
                channel.id,
                "fixture",
                AdCensus(3_720_000, marked = true, breaks = 4, learned = 1),
                java.time.Instant.now(),
            )
            browser.signIn()
            val page = browser.get("/admin/ads").bodyAsText()
            assertTrue(page.contains(channel.name), "the channel shows by name")
            assertTrue(page.contains("1 h 2 min"), "the time watched")
            assertTrue(page.contains("Shadow: logged, never blanked"), "the default mode")
            assertTrue(
                browser.get("/admin/providers").bodyAsText().contains("""href="/admin/ads""""),
                "Sources links its Ads tab",
            )
        }
    }

    @Test
    fun `watching now lists each session with its player, and Stop ends it after a confirmation`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val station = runtime.lineup.entries().single {
                it.name == "Station" &&
                    runtime.feeds.published.offering(it.offeringIds.first())?.providerId == "fixture"
            }
            val opened = browser.post("/api/v1/playback-sessions") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${station.id}","intent":"watch","idempotencyKey":"portal-watch"}""")
            }
            assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
            val sessionId = Json.parseToJsonElement(opened.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            val overview = browser.get("/admin").bodyAsText()
            assertTrue(
                overview.contains("1 session") && overview.contains("Sofa King app") && overview.contains("localhost"),
                "the session, its player and its address are listed: $overview",
            )
            assertTrue(
                overview.contains("/admin/lineup/${station.id}") && overview.contains("via fixture"),
                "the channel links to its page and names its source",
            )
            if (station.guide != null) assertTrue(overview.contains("Morning"), "the program on now is named")
            assertTrue(overview.contains("/admin/sessions/$sessionId/stop"), "each session has a Stop")
            val listed = Json.parseToJsonElement(
                browser.get("/api/v1/admin/sessions") {
                    bearerAuth(admin)
                }.bodyAsText(),
            ).jsonArray.single().jsonObject
            assertEquals("localhost", listed["client"]!!.jsonPrimitive.content)
            assertEquals(0, listed["bytes"]!!.jsonPrimitive.int)

            val csrf = field(overview, "csrf")
            val asked = browser.post("/admin/sessions/$sessionId/stop", "csrf" to csrf)
            assertEquals(HttpStatusCode.OK, asked.status)
            assertTrue(
                asked.bodyAsText().contains("role=\"dialog\"") &&
                    asked.bodyAsText().contains("Stop the session on Station?"),
                "stopping asks first",
            )
            assertEquals(1, runtime.sessions.count(), "nothing stops before the confirm")
            val stopped = browser.post("/admin/sessions/$sessionId/stop", "csrf" to csrf, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, stopped.status)
            assertEquals(0, runtime.sessions.count())
            assertTrue(browser.get("/admin").bodyAsText().contains("Stopped the session on Station."))
            val again = browser.post("/admin/sessions/$sessionId/stop", "csrf" to csrf, "confirm" to "yes")
            assertEquals(
                HttpStatusCode.SeeOther,
                again.status,
                "a session that already ended is reported, not an error",
            )
        }
    }

    @Test fun `Switch on a channel with one source leaves the session on it`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val station = runtime.lineup.entries().first { it.name == "Station" }
            val opened = browser.post("/api/v1/playback-sessions") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${station.id}","intent":"watch","idempotencyKey":"portal-switch"}""")
            }
            val sessionId = Json.parseToJsonElement(opened.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            val overview = browser.get("/admin").bodyAsText()
            assertTrue(overview.contains("/admin/sessions/$sessionId/switch"), "each session has a Switch")
            assertTrue(overview.contains("no stalls"), "and its stalls")
            val switched = browser.post("/admin/sessions/$sessionId/switch", "csrf" to field(overview, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, switched.status)
            assertTrue(
                browser.get("/admin").bodyAsText().contains("Not switched: The channel has no other eligible"),
                "a channel with one source stays on it",
            )
            val refused = browser.post("/api/v1/admin/sessions/$sessionId/switch-source") { bearerAuth(admin) }
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals(1, runtime.sessions.count(), "the session plays on")
        }
    }

    @Test fun `the API page renders the OpenAPI document behind the session`() = testApplication {
        portal {
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/api").status)
            assertEquals(
                HttpStatusCode.SeeOther,
                browser.get("/admin/api/openapi.json").status,
                "the document needs the session too",
            )
            browser.signIn()
            val page = browser.get("/admin/api")
            assertEquals(HttpStatusCode.OK, page.status)
            val html = page.bodyAsText()
            for (shown in listOf(
                "/api/v1/channels",
                "/api/v1/admin/sessions",
                "/output/guide.xml",
                "PATCH",
                "Takes: admin token",
                "Download the OpenAPI document",
                "id=\"api-playback\"",
            )) {
                assertTrue(html.contains(shown), shown)
            }
            assertTrue(html.contains("aria-current=\"page\">API<"), "the nav marks the page")
            val document = browser.get("/admin/api/openapi.json")
            assertEquals(HttpStatusCode.OK, document.status)
            assertEquals(ContentType.Application.Json, document.contentType()?.withoutParameters())
            val spec = Json.parseToJsonElement(document.bodyAsText()).jsonObject
            assertEquals("3.1.0", spec["openapi"]!!.jsonPrimitive.content)
            val paths = spec["paths"]!!.jsonObject.keys
            for (path in listOf(
                "/healthz",
                "/api/v1/bootstrap",
                "/api/v1/playback-sessions/{id}",
                "/api/v1/admin/policy/apply",
                "/api/v1/admin/virtual-channels/{id}/schedule",
                "/auto/v{number}",
            )) {
                assertTrue(path in paths, path)
            }
        }
    }

    @Test
    fun `the viewers page issues a pairing code with its QR code, lists devices and forgets one`() = testApplication {
        portal {
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/viewers").status)
            browser.signIn()
            val empty = browser.get("/admin/viewers")
            assertEquals(HttpStatusCode.OK, empty.status)
            val emptyHtml = empty.bodyAsText()
            assertTrue(emptyHtml.contains("Show a pairing code"))
            assertTrue(emptyHtml.contains("No device has paired"))
            assertFalse(emptyHtml.contains("pairing-code"))
            assertTrue(emptyHtml.contains("aria-current=\"page\">Devices<"), "the nav marks the page")

            val issued = browser.post("/admin/viewers/pair", "csrf" to field(emptyHtml, "csrf"))
            assertEquals(HttpStatusCode.OK, issued.status)
            val issuedHtml = issued.bodyAsText()
            val code = runtime.pairing.current()!!.code
            assertTrue(issuedHtml.contains(spacedCode(code)), "the code is on the page")
            assertTrue(
                issuedHtml.contains("<svg") && issuedHtml.contains("Pairing code $code"),
                "the QR code is drawn inline",
            )
            assertTrue(
                issuedHtml.contains("Pairing code $code for http://"),
                "the QR is labeled with the code and the address",
            )
            assertTrue(issuedHtml.contains("Cancel the code"))
            assertTrue(
                browser.get("/admin/viewers").bodyAsText().contains(spacedCode(code)),
                "a reload shows the live code",
            )

            // A device claims the code and saves its preferences; it then appears with them.
            val api = createClient { }
            val paired = api.post("/api/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$code","clientId":"den-tv","name":"Den TV","platform":"android-tv"}""")
            }
            assertEquals(HttpStatusCode.OK, paired.status)
            api.put("/api/v1/preferences/den-tv") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"hidden":["a","b"],"favorites":["c"],"recent":["c","d","e"]}""")
            }
            val listed = browser.get("/admin/viewers").bodyAsText()
            assertFalse(listed.contains("pairing-code"), "the claimed code is gone")
            for (shown in listOf(
                "Den TV",
                "den-tv",
                "android-tv",
                "id=\"viewer-den-tv\"",
            )) {
                assertTrue(listed.contains(shown), shown)
            }
            assertFalse(listed.contains(token), "the API token never reaches the page")

            val forgot = browser.post("/admin/viewers/den-tv/forget", "csrf" to field(listed, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, forgot.status)
            assertTrue(browser.get("/admin/viewers").bodyAsText().contains("No device has paired"))
            assertEquals(HttpStatusCode.NotFound, api.get("/api/v1/preferences/den-tv") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun `the library page adds, scans, renames and removes a library without touching its files`() = testApplication {
        portal {
            val movies = Files.createDirectories(dataDirectory.resolve("media/movies/Alien (1979)")).parent
            Files.writeString(movies.resolve("Alien (1979)/Alien (1979).mkv"), "not really a movie")
            browser.signIn()
            val page = browser.get("/admin/library").bodyAsText()
            assertTrue(page.contains("No library yet"), page)
            assertTrue(page.contains(">Library<"), "the nav names the destination")
            assertTrue(page.contains("""value="$movies""""), "the folder is offered")
            val csrf = field(page, "csrf")

            val refused = browser.post(
                "/admin/library",
                "csrf" to csrf,
                "name" to "",
                "kind" to "movies",
                "path" to movies.toString(),
            )
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText().contains("1 to 100 characters"))

            assertEquals(
                HttpStatusCode.SeeOther,
                browser.post(
                    "/admin/library",
                    "csrf" to csrf,
                    "name" to "Movies",
                    "kind" to "movies",
                    "path" to movies.toString(),
                ).status,
            )
            val library = runtime.libraries!!.libraries().single()
            runtime.libraries!!.scan(library.id)
            val listed = browser.get("/admin/library").bodyAsText()
            assertTrue(listed.contains("1 movie"), listed)

            browser.post("/admin/library/${library.id}/rename", "csrf" to csrf, "name" to "Films")
            assertEquals("Films", runtime.libraries!!.library(library.id).name)

            val ask = browser.post("/admin/library/${library.id}/remove", "csrf" to csrf)
            assertTrue(ask.bodyAsText().contains("Remove Films?"), "removal asks first")
            assertEquals(1, runtime.libraries!!.libraries().size)
            browser.post("/admin/library/${library.id}/remove", "csrf" to csrf, "confirm" to "yes")
            assertTrue(runtime.libraries!!.libraries().isEmpty())
            assertTrue(Files.exists(movies.resolve("Alien (1979)/Alien (1979).mkv")), "the files stay")
        }
    }

    /** The JSON the app's API answers at [path], read with the API token. */
    private suspend fun ApplicationTestBuilder.api(path: String): JsonObject =
        Json.parseToJsonElement(client.get(path) { bearerAuth(token) }.bodyAsText()).jsonObject

    @Test fun `the recordings page adds, edits and deletes a rule, as the API sees it`() = testApplication {
        portal {
            browser.signIn()
            val empty = browser.get("/admin/recordings").bodyAsText()
            assertTrue(
                empty.contains("Nothing is scheduled") && empty.contains("No recordings yet") &&
                    empty.contains("Keeps 10 GiB free"),
                empty,
            )
            assertTrue(empty.contains("aria-current=\"page\">Recordings<"), "the nav marks the destination")
            val csrf = field(empty, "csrf")

            val rule = arrayOf("title" to "Evening News", "channelId" to "", "keep" to "3", "newOnly" to "true")
            assertEquals(HttpStatusCode.SeeOther, browser.post("/admin/recordings/rules", *rule, "csrf" to csrf).status)
            val withRule = browser.get("/admin/recordings").bodyAsText()
            assertTrue(
                withRule.contains("Added the rule for Evening News") && withRule.contains("Any channel"),
                withRule,
            )
            assertTrue(withRule.contains("1 min before, 2 min after"), withRule)
            val refused = browser.post("/admin/recordings/rules", "title" to " ", "keep" to "3", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, refused.status)
            assertTrue(browser.get("/admin/recordings").bodyAsText().contains("Not added: A rule needs a title"))
            val rules = api("/api/v1/recording-rules")["rules"]!!.jsonArray
            assertEquals(1, rules.size)
            val ruleId = rules.single().jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(3, rules.single().jsonObject["keep"]!!.jsonPrimitive.int)

            // Editing sets the padding and priority the add form left at their defaults.
            browser.post(
                "/admin/recordings/rules/$ruleId",
                "channelId" to "",
                "keep" to "5",
                "startPadding" to "3",
                "endPadding" to "10",
                "priority" to "7",
                "csrf" to csrf,
            )
            val edited = browser.get("/admin/recordings").bodyAsText()
            assertTrue(
                edited.contains("Saved the rule for Evening News") && edited.contains("3 min before, 10 min after") &&
                    edited.contains("All airings"),
                edited,
            )
            val saved = api("/api/v1/recording-rules")["rules"]!!.jsonArray.single().jsonObject
            assertEquals(180, saved["startPaddingSeconds"]!!.jsonPrimitive.int)
            assertEquals(600, saved["endPaddingSeconds"]!!.jsonPrimitive.int)
            assertEquals(7, saved["priority"]!!.jsonPrimitive.int)
            assertEquals(5, saved["keep"]!!.jsonPrimitive.int)
            assertFalse(saved["newOnly"]!!.jsonPrimitive.boolean)

            browser.post("/admin/recordings/rules/$ruleId/delete", "csrf" to csrf)
            assertFalse(browser.get("/admin/recordings").bodyAsText().contains("Evening News"))
            assertTrue(api("/api/v1/recording-rules")["rules"]!!.jsonArray.isEmpty())
        }
    }

    @Test fun `a time window from the recordings page records any enabled channel, guide or not`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val csrf = field(browser.get("/admin/recordings").bodyAsText(), "csrf")
            val channelId = runtime.lineup.entries().first { it.enabled }.id
            val badWindow = browser.post(
                "/admin/recordings/manual",
                "channelId" to channelId,
                "start" to "",
                "minutes" to "30",
                "csrf" to csrf,
            )
            assertEquals(HttpStatusCode.SeeOther, badWindow.status)
            assertTrue(
                browser.get("/admin/recordings").bodyAsText().contains("Not scheduled: Pick a start date and time"),
            )
            val startsAt = java.time.LocalDateTime.now().plusDays(1).withSecond(0).withNano(0)
            browser.post(
                "/admin/recordings/manual",
                "channelId" to channelId,
                "start" to startsAt.toString(),
                "minutes" to "45",
                "title" to "Late Game",
                "csrf" to csrf,
            )
            val manualPage = browser.get("/admin/recordings").bodyAsText()
            assertTrue(manualPage.contains("Scheduled Late Game"), manualPage)
            val manual = api("/api/v1/recordings")["recordings"]!!.jsonArray
                .single { it.jsonObject["title"]!!.jsonPrimitive.content == "Late Game" }.jsonObject
            val start = java.time.Instant.parse(manual["startsAt"]!!.jsonPrimitive.content)
            assertEquals(startsAt.atZone(java.time.ZoneId.systemDefault()).toInstant(), start)
            assertEquals(start.plusSeconds(45 * 60), java.time.Instant.parse(manual["endsAt"]!!.jsonPrimitive.content))
            val id = manual["id"]!!.jsonPrimitive.content
            browser.post("/admin/recordings/$id/delete", "csrf" to csrf)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/recordings/$id") { bearerAuth(token) }.status)
        }
    }

    @Test fun `the recordings page protects and deletes a recording, as the API sees it`() = testApplication {
        portal {
            val now = java.time.Instant.parse("2026-10-17T12:00:00Z")
            runtime.store.addRecording(
                dev.jamesrich.sofaking.backend.dvr.StoredRecording(
                    "rec-1", null, "c", "Channel C", now, now.plusSeconds(1800),
                    java.time.Duration.ZERO, java.time.Duration.ZERO, "Old Movie",
                    state = Wire.RecordingState.COMPLETED,
                    path = "Movies/Old Movie (1950)/Old Movie (1950).ts",
                    bytes = 2_000_000, createdAt = now, updatedAt = now,
                ),
            )
            browser.signIn()
            val listed = browser.get("/admin/recordings").bodyAsText()
            assertTrue(
                listed.contains("id=\"recording-rec-1\"") && listed.contains("Old Movie (1950).ts") &&
                    listed.contains("Recorded"),
                listed,
            )
            val csrf = field(listed, "csrf")
            browser.post("/admin/recordings/rec-1/protect", "protected" to "true", "csrf" to csrf)
            val protectedPage = browser.get("/admin/recordings").bodyAsText()
            assertTrue(
                protectedPage.contains("cleanup keeps it") && protectedPage.contains("Unprotect"),
                protectedPage,
            )
            assertTrue(api("/api/v1/recordings/rec-1")["protected"]!!.jsonPrimitive.boolean)

            browser.post("/admin/recordings/rec-1/delete", "csrf" to csrf)
            assertTrue(browser.get("/admin/recordings").bodyAsText().contains("No recordings yet"))
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/recordings/rec-1") { bearerAuth(token) }.status)
        }
    }
}
