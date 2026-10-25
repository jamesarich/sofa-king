package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.catalog.stationId
import dev.jamesrich.sofaking.backend.health.FailureClass
import dev.jamesrich.sofaking.backend.health.Observation
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.backend.store.recordObservation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.content
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.http.path
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lineup and station pages, and the edits made from them. */
class AdminPortalLineupTest : AdminPortalCase() {
    @Test fun `hide writes the policy override, and the lineup reflects it after the apply`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            for (path in listOf(
                "/admin",
                "/admin/merges",
                "/admin/required",
                "/admin/plex",
            )) {
                assertEquals(HttpStatusCode.OK, browser.get(path).status, path)
            }
            val page = browser.get("/admin/lineup").bodyAsText()
            val id = stationId("twin sports")
            val station = browser.get("/admin/lineup/$id").bodyAsText()
            assertTrue(
                Regex(
                    """<select name="group"[^>]*><option value=""[^>]*>Policy decides</option>""",
                ).containsMatchIn(station),
                "the group is picked from a list",
            )
            val csrf = field(page, "csrf")
            val revision = field(page, "revision")
            val response = browser.post(
                "/admin/lineup/$id/visibility",
                "enabled" to "false",
                "csrf" to csrf,
                "revision" to revision,
            )
            assertEquals(HttpStatusCode.SeeOther, response.status, response.bodyAsText())
            assertEquals(
                false,
                stationsIn(policyFile!!).getValue("twin sports").jsonObject["enabled"]!!.jsonPrimitive.boolean,
            )
            assertFalse(runtime.lineup.entry(id)!!.enabled)
            assertTrue(browser.get("/admin/lineup?state=hidden").bodyAsText().contains("off: policy"))

            // The file has moved on since the page loaded; a one-station edit still applies to it as it is now.
            val later = browser.post(
                "/admin/lineup/$id/visibility",
                "enabled" to "true",
                "csrf" to csrf,
                "revision" to revision,
            )
            assertEquals(HttpStatusCode.SeeOther, later.status, later.bodyAsText())
            assertTrue(runtime.lineup.entry(id)!!.enabled)
        }
    }

    @Test fun `never merge moves a learned alias to names never`() = testApplication {
        portal("""{"health":{"requireVerified":false},"providers":[{"id":"learn"},{"id":"learn2"}]}""", feeds = {
            listOf(it.learnFeed, it.learnTwinFeed)
        }) {
            for (guide in runtime.guides.configs) runtime.guides.refresh(guide)
            runtime.policy!!.apply("test")
            for (guide in runtime.guides.configs) runtime.guides.refresh(guide)
            assertEquals(mapOf("learnshowtv" to "learnshow"), runtime.policy.apply("test").learned)

            browser.signIn()
            val page = browser.get("/admin/merges").bodyAsText()
            assertTrue(page.contains("learnshowtv") && page.contains("Never merge"))
            val response = browser.post(
                "/admin/merges/never",
                "from" to "learnshowtv",
                "to" to "learnshow",
                "csrf" to field(page, "csrf"),
                "revision" to field(page, "revision"),
            )
            assertEquals(HttpStatusCode.SeeOther, response.status, response.bodyAsText())
            val names = Json.parseToJsonElement(Files.readString(policyFile!!)).jsonObject["names"]!!.jsonObject
            assertEquals(Json.parseToJsonElement("""[["learnshowtv","learnshow"]]"""), names["never"])
            assertNull(names["learned"], "the learned entry goes")
            assertTrue(
                runtime.lineup.entries().any {
                    it.name == "Learn Show TV"
                },
                "the station is its own again: ${runtime.lineup.entries().map { it.name }}",
            )
        }
    }

    @Test fun `the station page edits a channel and the lineup confirms the save inline`() = testApplication {
        portal {
            runtime.adoptAll()
            val twin = runtime.lineup.entries().single { it.name == "Twin Sports" }
            assertEquals(
                HttpStatusCode.SeeOther,
                browser.get("/admin/lineup/${twin.id}").status,
                "the station page needs the session",
            )
            browser.signIn()
            val page = browser.get("/admin/lineup/${twin.id}?q=twin")
            assertEquals(HttpStatusCode.OK, page.status)
            val html = page.bodyAsText()
            assertTrue(html.contains("Sources, in failover order") && html.contains("twin"))
            assertFalse(html.contains("/twin-feed.m3u"), "source URLs stay off the page")
            val gone = browser.get("/admin/lineup/not-a-station")
            assertEquals(HttpStatusCode.Gone, gone.status)
            assertTrue(gone.bodyAsText().contains("Channel not found"))

            val saved = browser.post(
                "/admin/lineup/${twin.id}/edit",
                "name" to "Twin Sports West",
                "number" to twin.number,
                "group" to "",
                "q" to "twin",
                "csrf" to field(html, "csrf"),
            )
            assertEquals(HttpStatusCode.SeeOther, saved.status)
            assertEquals("/admin/lineup?q=twin", saved.headers[HttpHeaders.Location])
            val list = browser.get("/admin/lineup?q=twin").bodyAsText()
            assertTrue(list.contains("role=\"status\">Saved Twin Sports West."), "the save is confirmed once")
            assertTrue(list.contains("<tr class=\"flash\" id=\"row-${twin.id}\""), "the saved row is marked")
            assertFalse(
                browser.get("/admin/lineup?q=twin").bodyAsText().contains("Saved Twin Sports West."),
                "the notice shows once",
            )

            val shown = browser.post(
                "/admin/lineup/${twin.id}/visibility",
                "enabled" to "false",
                "from" to "station",
                "csrf" to field(html, "csrf"),
            )
            assertEquals(
                "/admin/lineup/${twin.id}",
                shown.headers[HttpHeaders.Location],
                "an action from the station page returns to it",
            )
        }
    }

    @Test fun `a rejected station edit shows the policy's error on the station page`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val id = stationId("twin sports")
            val other = runtime.lineup.entries().first { it.id != id }
            val html = browser.get("/admin/lineup/$id").bodyAsText()
            val response = browser.post(
                "/admin/lineup/$id/edit",
                "name" to "Twin Sports",
                "number" to other.number,
                "group" to "",
                "csrf" to field(html, "csrf"),
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Not saved:") && body.contains("is claimed by"), body)
            assertTrue(body.contains("name=\"number\""), "the form comes back to correct")
            assertNotEquals(other.number, runtime.lineup.entry(id)!!.number)
        }
    }

    @Test fun `the guide filter splits the lineup`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val all = Regex("""id="row-""").findAll(browser.get("/admin/lineup").bodyAsText()).count()
            val with = Regex("""id="row-""").findAll(browser.get("/admin/lineup?guide=available").bodyAsText()).count()
            val without = Regex("""id="row-""").findAll(browser.get("/admin/lineup?guide=missing").bodyAsText()).count()
            assertTrue(all > 0)
            assertEquals(all, with + without)
        }
    }

    @Test fun `the channels table sorts by column, tints rows without a healthy source and filters overrides`() =
        testApplication {
            portal(basePolicy) {
                runtime.policy!!.apply("test")
                browser.signIn()
                val plain = browser.get("/admin/lineup").bodyAsText()
                assertTrue(plain.contains("<caption class=\"sr\">Channels</caption>"), "every table has a caption")
                assertTrue(plain.contains("<th class=\"num\" scope=\"col\">"), "headers carry their scope")
                assertTrue(plain.contains("class=\"tint-bad\""), "an on-air station with no healthy source is tinted")
                assertTrue(
                    plain.contains("No healthy source: no source has passed a probe yet"),
                    "a red row says why",
                )
                fun names(html: String) =
                    Regex("""class="rowlink">([^<]+)</a>""").findAll(html).map { it.groupValues[1] }.toList()
                val desc = browser.get("/admin/lineup?sort=name&dir=desc").bodyAsText()
                assertTrue(desc.contains("aria-sort=\"descending\""), desc.take(2000))
                assertEquals(names(desc).sortedWith(String.CASE_INSENSITIVE_ORDER).reversed(), names(desc))
                val asc = browser.get("/admin/lineup?sort=name").bodyAsText()
                assertEquals(names(asc).sortedWith(String.CASE_INSENSITIVE_ORDER), names(asc))
                assertTrue(
                    asc.contains("href=\"/admin/lineup?sort=name&amp;dir=desc\""),
                    "the sorted header's link flips the direction",
                )

                assertEquals(
                    0,
                    Regex("""id="row-""").findAll(browser.get("/admin/lineup?override=true").bodyAsText()).count(),
                    "no station has an override yet",
                )
                val id = stationId("twin sports")
                val hidden = browser.post(
                    "/admin/lineup/$id/visibility",
                    "enabled" to "false",
                    "csrf" to field(plain, "csrf"),
                    "revision" to field(plain, "revision"),
                )
                assertEquals(HttpStatusCode.SeeOther, hidden.status)
                val overridden = browser.get("/admin/lineup?override=true").bodyAsText()
                assertEquals(1, Regex("""id="row-""").findAll(overridden).count())
                assertTrue(overridden.contains("Twin Sports") && overridden.contains(">Override<"), overridden)
                val changed = browser.get("/admin/lineup?changed=true").bodyAsText()
                assertTrue(changed.contains("Twin Sports"), "the hide was the last run's change")
            }
        }

    @Test fun `a source that is not healthy says why, over its newest probe results`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val station = runtime.lineup.entries().first { it.offeringIds.isNotEmpty() }
            val offeringId = station.offeringIds.first()
            val now = Instant.now()
            runtime.store.recordObservation(
                offeringId,
                Observation("refused", now.minusSeconds(600), false, FailureClass.AUTH),
                now,
            )
            runtime.store.recordObservation(
                offeringId,
                Observation("gone", now, false, FailureClass.OFFERING),
                now,
            )
            val probed = browser.get("/admin/lineup/${station.id}").bodyAsText()
            assertTrue(
                probed.contains("The source failed: not found, no stream") &&
                    probed.contains("the provider refused access (401 or 403)") &&
                    probed.indexOf("refused access") > probed.indexOf("Failed "),
                "a suspect source says why, over its probe results, newest first: $probed",
            )
        }
    }

    @Test fun `a source is disabled from the channel page after a confirmation, and enabled again at once`() =
        testApplication {
            portal {
                runtime.adoptAll()
                browser.signIn()
                val station = runtime.lineup.entries().single {
                    it.name == "Station" &&
                        runtime.feeds.published.offering(it.offeringIds.first())?.providerId == "fixture"
                }
                val offeringId = station.offeringIds.first()
                val page = browser.get("/admin/lineup/${station.id}?q=stat").bodyAsText()
                assertTrue(
                    page.contains("/admin/lineup/${station.id}/sources/$offeringId/disable") &&
                        page.contains(">Disable<"),
                    page,
                )
                assertTrue(page.contains("Probes are off on this server"), "no Probe now without a probe worker")
                val csrf = field(page, "csrf")
                val asked = browser.post(
                    "/admin/lineup/${station.id}/sources/$offeringId/disable",
                    "disabled" to "true",
                    "q" to "stat",
                    "csrf" to csrf,
                )
                assertEquals(HttpStatusCode.OK, asked.status)
                assertTrue(
                    asked.bodyAsText().contains("role=\"dialog\"") &&
                        asked.bodyAsText().contains("Disable source 1 from fixture?"),
                    asked.bodyAsText(),
                )
                assertFalse(runtime.store.health(offeringId)!!.manuallyDisabled, "nothing changes before the confirm")
                val disabled = browser.post(
                    "/admin/lineup/${station.id}/sources/$offeringId/disable",
                    "disabled" to "true",
                    "q" to "stat",
                    "csrf" to csrf,
                    "confirm" to "yes",
                )
                assertEquals(HttpStatusCode.SeeOther, disabled.status, disabled.bodyAsText())
                assertEquals("/admin/lineup/${station.id}?q=stat", disabled.headers[HttpHeaders.Location])
                assertTrue(runtime.store.health(offeringId)!!.manuallyDisabled)
                val after = browser.get("/admin/lineup/${station.id}").bodyAsText()
                assertTrue(
                    after.contains("Disabled the source from fixture.") && after.contains("Disabled by hand") &&
                        after.contains(">Enable<"),
                    after,
                )
                val enabled = browser.post(
                    "/admin/lineup/${station.id}/sources/$offeringId/disable",
                    "disabled" to "false",
                    "csrf" to csrf,
                )
                assertEquals(HttpStatusCode.SeeOther, enabled.status)
                assertFalse(runtime.store.health(offeringId)!!.manuallyDisabled)
                val probe = browser.post("/admin/lineup/${station.id}/sources/$offeringId/probe", "csrf" to csrf)
                assertEquals(HttpStatusCode.SeeOther, probe.status)
                assertTrue(
                    browser.get("/admin/lineup/${station.id}").bodyAsText().contains("Probes are off on this server."),
                )
            }
        }

    @Test
    fun `a bulk edit writes every selected station's override in one policy edit, once confirmed`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin/lineup").bodyAsText()
            assertTrue(
                page.contains("form=\"bulk\"") && page.contains("Select all on this page"),
                "every row has a checkbox of the bulk form",
            )
            assertFalse(page.contains("checked"), "nothing is selected at first")
            assertTrue(
                browser.get("/admin/lineup?select=page").bodyAsText().contains("checked"),
                "the link selects the page",
            )
            val ids = listOf(stationId("twin sports"), stationId("ts station"))
            val csrf = field(page, "csrf")
            val revision = field(page, "revision")
            val fields = arrayOf("op" to "hide", "id" to ids[0], "id" to ids[1], "csrf" to csrf, "revision" to revision)
            val asked = browser.post("/admin/lineup/bulk", *fields)
            assertEquals(HttpStatusCode.OK, asked.status)
            val dialog = asked.bodyAsText()
            assertTrue(
                dialog.contains("Save: hide 2 channels?") && dialog.contains("2 channels turn off") &&
                    dialog.contains("Twin Sports") && dialog.contains("TS Station"),
                dialog,
            )
            assertTrue(
                dialog.contains("value=\"${ids[0]}\" form=\"bulk\" aria-label=\"Select Twin Sports\" checked"),
                "the selection is kept while the edit waits",
            )
            assertTrue(
                stationsIn(policyFile!!).values.none { "enabled" in it.jsonObject },
                "nothing is saved before the confirm; the run only learns numbers",
            )
            val saved = browser.post("/admin/lineup/bulk", *fields, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals("/admin/lineup", saved.headers[HttpHeaders.Location])
            val stations = stationsIn(policyFile)
            assertEquals(false, stations.getValue("twin sports").jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertEquals(false, stations.getValue("ts station").jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertFalse(runtime.lineup.entry(ids[0])!!.enabled)
            assertFalse(runtime.lineup.entry(ids[1])!!.enabled)
            assertTrue(browser.get("/admin/lineup").bodyAsText().contains("Saved: hide 2 channels."))
        }
    }

    @Test fun `a bulk edit needs a selection, and a group edit keeps the lineup's filters`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin/lineup").bodyAsText()
            val csrf = field(page, "csrf")
            val revision = field(page, "revision")
            val none = browser.post("/admin/lineup/bulk", "op" to "hide", "csrf" to csrf, "revision" to revision)
            assertEquals(HttpStatusCode.BadRequest, none.status)
            assertTrue(none.bodyAsText().contains("Select at least one channel first."))

            val twin = stationId("twin sports")
            val grouped = browser.post(
                "/admin/lineup/bulk",
                "op" to "group",
                "target" to "Sports",
                "id" to twin,
                "q" to "twin",
                "csrf" to csrf,
                "revision" to runtime.policy.document().revision,
                "confirm" to "yes",
            )
            assertEquals(HttpStatusCode.SeeOther, grouped.status, grouped.bodyAsText())
            assertEquals("/admin/lineup?q=twin", grouped.headers[HttpHeaders.Location], "the filters survive the edit")
            assertEquals(
                "Sports",
                stationsIn(policyFile!!).getValue("twin sports").jsonObject["group"]!!.jsonPrimitive.content,
            )
            assertEquals("Sports", runtime.lineup.entry(twin)!!.group)
        }
    }

    @Test fun `without a policy a bulk edit changes the lineup entries after a confirmation`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val page = browser.get("/admin/lineup").bodyAsText()
            val entries = runtime.lineup.entries().filter { it.name in setOf("TS Station", "Twin Sports") }
            val fields = entries.map { "id" to it.id }.toTypedArray()
            val asked = browser.post("/admin/lineup/bulk", "op" to "hide", *fields, "csrf" to field(page, "csrf"))
            assertEquals(HttpStatusCode.OK, asked.status)
            assertTrue(asked.bodyAsText().contains("2 channels turn off"), asked.bodyAsText())
            assertTrue(entries.all { runtime.lineup.entry(it.id)!!.enabled })
            val saved = browser.post(
                "/admin/lineup/bulk",
                "op" to "hide",
                *fields,
                "csrf" to field(page, "csrf"),
                "confirm" to "yes",
            )
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertTrue(entries.none { runtime.lineup.entry(it.id)!!.enabled })
            val regrouped = browser.post(
                "/admin/lineup/bulk",
                "op" to "group",
                "target" to "News",
                *fields,
                "csrf" to field(page, "csrf"),
                "confirm" to "yes",
            )
            assertEquals(HttpStatusCode.SeeOther, regrouped.status)
            assertTrue(entries.all { runtime.lineup.entry(it.id)!!.group == "News" })
        }
    }
}
