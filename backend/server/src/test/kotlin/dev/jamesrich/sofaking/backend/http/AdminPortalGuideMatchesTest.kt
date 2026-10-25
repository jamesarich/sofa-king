package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.GuideMatch
import dev.jamesrich.sofaking.backend.api.GuideMatchResult
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guide matches: channels proposed by name for a station no guide lists, accepted or dismissed. */
class AdminPortalGuideMatchesTest : AdminPortalCase() {
    private val matchPolicy = basePolicy.dropLast(1) + ""","guideMatches":{"ignorePrefixes":["US:"]}}"""

    @Test fun `accepting a proposal writes the station's guide and drops its proposals`() = testApplication {
        portal(matchPolicy, guides = { listOf(it.nameMatchGuide) }) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin/guide-matches").bodyAsText()
            assertTrue(page.contains("US: TS Station HD"), "the cleaned name matches exactly")
            assertTrue(page.contains("100%"))
            assertTrue(page.contains("TS Stations"), "a close name is proposed too")
            assertFalse(page.contains("Weather Now"), "a name sharing no word is never proposed")
            val accepted = browser.post(
                "/admin/guide-matches/accept",
                "station" to "ts station",
                "guideId" to "names",
                "channelId" to "tsstation.us",
                "csrf" to field(page, "csrf"),
            )
            assertEquals(HttpStatusCode.SeeOther, accepted.status)
            val guide = stationsIn(policyFile!!)["ts station"]!!.jsonObject["guide"]!!.jsonObject
            assertEquals("names", guide["guideId"]!!.jsonPrimitive.content)
            assertEquals("tsstation.us", guide["channelId"]!!.jsonPrimitive.content)
            val after = browser.get("/admin/guide-matches").bodyAsText()
            assertTrue(after.contains("Accepted tsstation.us for ts station."))
            assertFalse(after.contains("TS Stations"), "a station with a guide has no proposals")
        }
    }

    @Test fun `a dismissed proposal is never proposed again`() = testApplication {
        portal(matchPolicy, guides = { listOf(it.nameMatchGuide) }) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin/guide-matches").bodyAsText()
            val dismissed = browser.post(
                "/admin/guide-matches/dismiss",
                "station" to "ts station",
                "guideId" to "names",
                "channelId" to "tsstations.us",
                "csrf" to field(page, "csrf"),
            )
            assertEquals(HttpStatusCode.SeeOther, dismissed.status)
            val listed = policyJson(policyFile!!)["guideMatches"]!!.jsonObject["dismissed"]!!.jsonArray
            assertEquals(1, listed.size)
            val after = browser.get("/admin/guide-matches").bodyAsText()
            assertFalse(after.contains("TS Stations"))
            assertTrue(after.contains("US: TS Station HD"), "the other proposal stays")
            val missing = browser.post("/admin/guide-matches/accept", "csrf" to field(after, "csrf"))
            assertEquals(HttpStatusCode.BadRequest, missing.status)
        }
    }

    @Test fun `the API lists proposals and accepts all above a confidence`() = testApplication {
        portal(matchPolicy, guides = { listOf(it.nameMatchGuide) }) {
            runtime.policy!!.apply("test")
            val listed = client.get("/api/v1/admin/policy/guide-matches") { bearerAuth(admin) }
            assertEquals(HttpStatusCode.OK, listed.status)
            val matches = Json.decodeFromString<List<GuideMatch>>(listed.bodyAsText())
            // The provider's own guide, imported in the background, may propose its Station too.
            val named = matches.filter { it.guideId == "names" }
            assertEquals(listOf("tsstation.us", "tsstations.us"), named.map { it.channelId })
            assertEquals(1.0, matches.first().confidence)
            val accepted = client.post("/api/v1/admin/policy/guide-matches/accept-all") {
                bearerAuth(admin)
                contentType(ContentType.Application.Json)
                setBody("""{"minConfidence":1.0}""")
            }
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertEquals(1, Json.decodeFromString<GuideMatchResult>(accepted.bodyAsText()).accepted)
            assertEquals(
                "tsstation.us",
                stationsIn(policyFile!!)["ts station"]!!.jsonObject["guide"]!!.jsonObject["channelId"]!!
                    .jsonPrimitive.content,
            )
            val left = client.get("/api/v1/admin/policy/guide-matches") { bearerAuth(admin) }.bodyAsText()
            assertTrue(Json.decodeFromString<List<GuideMatch>>(left).none { it.station == "ts station" })
        }
    }
}
