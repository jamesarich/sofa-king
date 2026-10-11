package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.stationAvailability
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class PolicyApplyTest {
    private val json = Json { encodeDefaults = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)
    private val basePolicy = """{"names":{"version":1},"providers":[{"id":"fixture"},{"id":"alt"},{"id":"twin"},{"id":"ts"}],"numbering":{"floor":100}}"""

    private class Setup(val dir: Path, val policyFile: Path, val runtime: LiveRuntime, val fixture: Fixture)

    /** A runtime over the fixture's feeds with [policy] in its own directory; the policy loop never fires on its own. */
    private suspend fun ApplicationTestBuilder.withPolicy(
        policy: String = basePolicy,
        feeds: (Fixture) -> List<FeedConfig> = { listOf(it.feed, it.altFeed, it.twinFeed, it.tsFeed) },
        block: suspend Setup.() -> Unit,
    ) {
        val dir = Files.createTempDirectory("sofa-policy-apply-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(feeds(fixture))},"guides":[]}""")
            val policyDir = Files.createDirectories(dir.resolve("policy"))
            val policyFile = policyDir.resolve("policy.json")
            Files.writeString(policyFile, policy)
            val store = openStore(dir)
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin, policyFile = policyFile)
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000)
            try {
                application { backendModule(store, MutableClock(), runtime, token, admin) }
                for (provider in runtime.feeds.configs) runtime.feeds.refresh(provider)
                Setup(dir, policyFile, runtime, fixture).block()
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }

    private fun HttpRequestBuilder.asAdmin() { bearerAuth(admin); contentType(ContentType.Application.Json) }

    private fun stationsIn(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject["stations"]?.jsonObject ?: JsonObject(emptyMap())

    @Test fun `apply writes the lineup, availability and change log, then writes assigned numbers back once`() = testApplication {
        withPolicy {
            runtime.adoptAll()
            val legacy = runtime.lineup.entries().map { it.id }.toSet()
            val first = Json.decodeFromString<PolicyApplyResult>(client.post("/api/v1/admin/policy/apply") { asAdmin() }.bodyAsText())
            assertTrue(first.changed)
            assertEquals(mapOf("station" to "5", "ts station" to "6", "twin sports" to "12"), first.assigned)

            val entries = runtime.lineup.entries()
            assertEquals(listOf("station", "ts station", "twin sports").map(::stationId), entries.map { it.id }, "lineup order is number order")
            assertEquals(listOf("5", "6", "12"), entries.map { it.number })
            assertTrue(entries.all { it.createdBy == "policy" && it.pinned.isEmpty() })
            assertEquals(listOf("fixture", "alt", "twin"), entries.first().offeringIds.map { runtime.feeds.offering(it)!!.providerId })

            assertEquals(setOf("station", "ts station", "twin sports"), runtime.store.stationAvailability().keys)
            val changes = Json.decodeFromString<List<CurationChange>>(client.get("/api/v1/admin/curation/changes?limit=100") { asAdmin() }.bodyAsText())
            assertTrue(changes.all { it.action.kind == "policy" && it.action.reason == "api" })
            assertEquals(setOf("station", "ts station", "twin sports"), changes.filter { it.action.field == "station" && it.action.before == null }.map { it.action.key }.toSet())
            assertEquals(legacy, changes.filter { it.action.field == "station" && it.action.after == null }.map { it.action.entryId }.toSet(), "every adopted entry is logged as removed")

            val written = stationsIn(policyFile)
            assertEquals(mapOf("station" to "5", "ts station" to "6", "twin sports" to "12"), written.mapValues { it.value.jsonObject["number"]!!.jsonPrimitive.content })
            assertEquals(hash(Files.readString(policyFile)), runtime.policy!!.document().revision)
            assertEquals(first.revision, runtime.policy!!.document().revision)

            val second = Json.decodeFromString<PolicyApplyResult>(client.post("/api/v1/admin/policy/apply") { asAdmin() }.bodyAsText())
            assertFalse(second.changed); assertEquals(0, second.changes); assertTrue(second.assigned.isEmpty())
            assertEquals(first.revision, second.revision, "nothing new to write back")

            // Without the written numbers this policy would number from the floor; the file's numbers hold.
            val revision = runtime.policy!!.document().revision
            val renumbering = Json.parseToJsonElement(Files.readString(policyFile)).jsonObject.toMutableMap()
            renumbering["numbering"] = Json.parseToJsonElement("""{"floor":500,"keepProviderNumbers":false}""")
            val put = client.put("/api/v1/admin/policy") { asAdmin(); header(HttpHeaders.IfMatch, "\"$revision\""); setBody(JsonObject(renumbering).toString()) }
            assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
            assertEquals(listOf("5", "6", "12"), runtime.lineup.entries().map { it.number })
        }
    }

    @Test fun `a write fails with 409 when the file changed on disk, and every write is atomic`() = testApplication {
        withPolicy {
            val revision = runtime.policy!!.document().revision
            assertEquals(hash(basePolicy), revision)
            assertEquals(HttpStatusCode.fromValue(428), client.put("/api/v1/admin/policy") { asAdmin(); setBody(basePolicy) }.status)
            val stale = client.put("/api/v1/admin/policy") { asAdmin(); header(HttpHeaders.IfMatch, "nope"); setBody(basePolicy) }
            assertEquals(HttpStatusCode.Conflict, stale.status); assertTrue(stale.bodyAsText().contains("POLICY_CHANGED"))

            val invalid = client.put("/api/v1/admin/policy") { asAdmin(); header(HttpHeaders.IfMatch, revision); setBody("""{"providers":[{"id":"nowhere"}]}""") }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("unknown provider nowhere"))
            assertEquals(basePolicy, Files.readString(policyFile), "a rejected write leaves the file as it was")
            assertEquals(listOf("policy.json"), Files.list(policyFile.parent).use { files -> files.map { it.fileName.toString() }.toList() })

            val edited = """{"providers":[{"id":"alt"},{"id":"fixture"}],"stations":{"station":{"number":"7","group":"Local"}}}"""
            val response = client.put("/api/v1/admin/policy") { asAdmin(); header(HttpHeaders.IfMatch, "W/\"$revision\""); setBody(edited) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val written = Json.decodeFromString<PolicyWriteResult>(response.bodyAsText())
            assertEquals(edited, Files.readString(policyFile), "the body is written as sent, never expanded")
            assertEquals(hash(edited), written.document.revision)
            assertEquals("\"${written.document.revision}\"", response.headers[HttpHeaders.ETag])
            assertEquals(listOf("policy.json"), Files.list(policyFile.parent).use { files -> files.map { it.fileName.toString() }.toList() }, "no temp file is left behind")
            assertEquals("7", runtime.lineup.entries().single { it.id == stationId("station") }.number)

            // Another editor changes the file on disk: the next write is refused and the server reloads the file.
            val external = """{"providers":[{"id":"fixture"}],"stations":{"station":{"number":"8"}}}"""
            Files.writeString(policyFile, external)
            val conflict = client.put("/api/v1/admin/policy") { asAdmin(); header(HttpHeaders.IfMatch, written.document.revision); setBody(edited) }
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals(external, Files.readString(policyFile))
            val reloaded = Json.decodeFromString<PolicyDocument>(client.get("/api/v1/admin/policy") { asAdmin() }.bodyAsText())
            assertEquals(hash(external), reloaded.revision)
            val patch = client.patch("/api/v1/admin/lineup/${stationId("station")}") { asAdmin(); header(HttpHeaders.IfMatch, written.document.revision); setBody("""{"name":"X"}""") }
            assertEquals(HttpStatusCode.Conflict, patch.status, "PATCH goes through the same revision check")
        }
    }

    @Test fun `PATCH and DELETE on a lineup entry write the station override and apply`() = testApplication {
        withPolicy {
            runtime.policy!!.apply("test")
            val fixtureOffering = runtime.feeds.offeringsOf(runtime.feeds.channels("fixture").single().id).single().id
            val patched = client.patch("/api/v1/admin/lineup/${stationId("station")}") {
                asAdmin(); setBody("""{"name":"Station East","group":"Local","logo":"https://logo.test/s.png","enabled":true,"offerings":["$fixtureOffering"]}""")
            }
            assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
            val entry = Json.decodeFromString<LineupEntry>(patched.bodyAsText())
            assertEquals("Station East", entry.name); assertEquals("Local", entry.group); assertEquals(fixtureOffering, entry.offerings.first().id)
            val override = stationsIn(policyFile).getValue("station").jsonObject
            assertEquals("Station East", override["name"]!!.jsonPrimitive.content)
            assertEquals("5", override["number"]!!.jsonPrimitive.content, "the written-back number stays")
            assertEquals(Json.parseToJsonElement("""[{"provider":"fixture","tvgId":"station","name":"Station"}]"""), override["sources"])

            assertEquals(HttpStatusCode.BadRequest, client.patch("/api/v1/admin/lineup/${stationId("station")}") { asAdmin(); setBody("""{"position":0}""") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.patch("/api/v1/admin/lineup/${stationId("station")}") { asAdmin(); setBody("""{"number":"12"}""") }.status, "a number another station holds is refused")

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/admin/lineup/${stationId("twin sports")}") { asAdmin() }.status)
            assertEquals(false, stationsIn(policyFile).getValue("twin sports").jsonObject["enabled"]!!.jsonPrimitive.boolean)
            val disabled = runtime.lineup.entry(stationId("twin sports"))!!
            assertFalse(disabled.enabled)
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/admin/lineup/${stationId("nothing")}") { asAdmin() }.status)
        }
    }

    @Test fun `routes the policy replaces answer 410 and the change log stays readable`() = testApplication {
        withPolicy {
            for ((method, path) in listOf(
                HttpMethod.Post to "/api/v1/admin/lineup", HttpMethod.Put to "/api/v1/admin/lineup/order", HttpMethod.Post to "/api/v1/admin/lineup/adopt",
                HttpMethod.Post to "/api/v1/admin/lineup/import", HttpMethod.Get to "/api/v1/admin/curation/settings", HttpMethod.Put to "/api/v1/admin/curation/settings",
                HttpMethod.Get to "/api/v1/admin/curation/rules", HttpMethod.Post to "/api/v1/admin/curation/run", HttpMethod.Get to "/api/v1/admin/curation/preview",
                HttpMethod.Post to "/api/v1/admin/curation/import", HttpMethod.Delete to "/api/v1/admin/curation/rules/x",
            )) {
                val response = client.request(path) { this.method = method; asAdmin(); setBody("{}") }
                assertEquals(HttpStatusCode.Gone, response.status, "$method $path")
                val error = Json.decodeFromString<ApiError>(response.bodyAsText())
                assertEquals("POLICY_MANAGED", error.code); assertTrue(error.message.contains("/api/v1/admin/policy"))
            }
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/curation/changes") { asAdmin() }.status)
            assertNull(runtime.curationLoop, "curation does not run under a policy")
        }
    }

    @Test fun `the cutover check passes a channel that lands in exactly one station`() = testApplication {
        withPolicy {
            val body = """
                {"exportedAt":"x","channels":[
                  {"name":"Station","number":"5","group":"News","logo":"https://l.test/a.png","sources":[{"provider":"fixture","channelName":"Station","tvgId":"station","status":"HEALTHY"},{"provider":"alt","tvgId":"station"}]},
                  {"name":"Gone","sources":[{"provider":"fixture","tvgId":"nope"}]},
                  {"name":"Spread","sources":[{"provider":"fixture","tvgId":"station"},{"provider":"twin","tvgId":"twin-sports"}]},
                  {"name":"Replaced","sources":[{"provider":"fixture","tvgId":"nope2"}]},
                  {"name":"Renamed","sources":[{"provider":"fixture","tvgId":"nope3"}]}
                ],
                "replacements":{"Replaced":[{"provider":"twin","tvgId":"twin-sports"}],"Renamed":{"name":"TS Station","sources":[{"provider":"ts","tvgId":"ts-station"}]}}}
            """.trimIndent()
            val report = Json.decodeFromString<PolicyCheckReport>(client.post("/api/v1/admin/policy/check") { asAdmin(); setBody(body) }.bodyAsText())
            val byName = report.channels.associateBy { it.name }
            assertTrue(byName.getValue("Station").pass); assertEquals(listOf("station"), byName.getValue("Station").stations)
            assertFalse(byName.getValue("Gone").pass); assertEquals(listOf("fixture/tvg-id:nope"), byName.getValue("Gone").missing)
            assertFalse(byName.getValue("Spread").pass); assertEquals("no station carries every source", byName.getValue("Spread").reason)
            assertTrue(byName.getValue("Replaced").pass); assertEquals(listOf("twin sports"), byName.getValue("Replaced").stations)
            assertTrue(byName.getValue("Renamed").pass)
            assertFalse(report.pass)
            assertTrue(report.duplicateNumbers.isEmpty() && report.duplicateIds.isEmpty())

            val passing = """{"channels":[{"name":"Station","sources":[{"provider":"fixture","tvgId":"station"}]}]}"""
            assertTrue(Json.decodeFromString<PolicyCheckReport>(client.post("/api/v1/admin/policy/check") { asAdmin(); setBody(passing) }.bodyAsText()).pass)
            assertTrue(runtime.lineup.entries().isEmpty(), "the check writes nothing")
        }
    }

    @Test fun `findings report a required station without a source, and the preview carries the same suggestions`() = testApplication {
        withPolicy("""{"names":{"version":1},"providers":[{"id":"fixture"}],"stations":{"nfl network":{"required":true,"sources":[{"provider":"alt","tvgId":"nfl"}]}}}""") {
            val findings = Json.decodeFromString<PolicyFindings>(client.get("/api/v1/admin/policy/findings") { asAdmin() }.bodyAsText())
            assertEquals(runtime.policy!!.document().revision, findings.revision)
            assertTrue("nfl network: required source missing: alt/tvg-id:nfl" in findings.findings, "${findings.findings}")
            assertTrue("nfl network: required station has no live source" in findings.findings)
            val preview = Json.decodeFromString<PolicyPreview>(client.get("/api/v1/admin/policy/preview") { asAdmin() }.bodyAsText())
            assertEquals(findings.suggestedAliases, preview.suggestedAliases)
            assertEquals(findings.findings, preview.findings)
        }
    }

    @Test fun `matching schedules teach an alias that is written back once, and different schedules never merge`() = testApplication {
        withPolicy("""{"providers":[{"id":"learn"},{"id":"learn2"}]}""", feeds = { listOf(it.learnFeed, it.learnTwinFeed) }) {
            for (guide in runtime.guides.configs) runtime.guides.refresh(guide)
            val first = runtime.policy!!.apply("test")
            assertTrue(first.learned.isEmpty(), "no programmes are kept before the lineup maps the guides")
            assertEquals(listOf("learnshowtv: possible alias of learnshow", "nflnetwork: possible alias of nflchannel"), first.suggestedAliases)
            assertEquals(setOf("learnshow", "learnshowtv", "nflnetwork", "nflchannel"), stationsIn(policyFile).keys)

            for (guide in runtime.guides.configs) runtime.guides.refresh(guide)
            val preview = Json.decodeFromString<PolicyPreview>(client.get("/api/v1/admin/policy/preview") { asAdmin() }.bodyAsText())
            assertEquals(mapOf("learnshowtv" to "learnshow"), preview.learned)
            val second = runtime.policy!!.apply("test")
            assertEquals(mapOf("learnshowtv" to "learnshow"), second.learned)
            assertTrue(second.suggestedAliases.isEmpty(), "${second.suggestedAliases}")
            val written = Json.parseToJsonElement(Files.readString(policyFile)).jsonObject
            assertEquals(JsonObject(mapOf("learnshowtv" to JsonPrimitive("learnshow"))), written["names"]!!.jsonObject["learned"])
            assertEquals(setOf("learnshow", "nflnetwork", "nflchannel"), stationsIn(policyFile).keys, "the merged-away station's number-only override goes")
            val entries = runtime.lineup.entries().associateBy { it.number }
            assertEquals(listOf("30", "31", "41"), entries.keys.toList())
            assertEquals(listOf("learn", "learn2"), entries.getValue("30").offeringIds.map { runtime.feeds.offering(it)!!.providerId })

            val third = runtime.policy!!.apply("test")
            assertTrue(third.learned.isEmpty()); assertFalse(third.changed)
            assertEquals(second.revision, third.revision, "nothing new to write back")
        }
    }
}

class PolicyLoopTest {
    @Test fun `with a policy, a feed import triggers a policy run and curation never starts`() = kotlinx.coroutines.runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-policy-loop-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${Json { encodeDefaults = true }.encodeToString(listOf(fixture.feed))},"guides":[]}""")
            val policyFile = dir.resolve("policy.json")
            Files.writeString(policyFile, """{"providers":[{"id":"fixture"}]}""")
            val store = openStore(dir)
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, "secret".repeat(8), allowLoopbackUpstreams = true, policyFile = policyFile)
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 10)
            try {
                assertNull(runtime.curationLoop)
                runtime.feeds.refresh(fixture.feed)
                withTimeout(10_000) { while (runtime.lineup.entries().isEmpty()) delay(20) }
                assertEquals(listOf(stationId("station")), runtime.lineup.entries().map { it.id })
                assertEquals("policy", runtime.lineup.entries().single().createdBy)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}
