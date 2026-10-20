package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.contract.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.Observation
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveStationAvailability
import dev.jamesrich.sofaking.backend.store.stationAvailability
import java.time.Duration
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class PolicyApplyTest {
    private val json = Json { encodeDefaults = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)
    private val basePolicy = """{"names":{"version":1},"health":{"requireVerified":false},"providers":[{"id":"fixture"},{"id":"alt"},{"id":"twin"},{"id":"ts"}],"numbering":{"floor":100}}"""

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
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000, probes = false)
            try {
                application { backendModule(store, MutableClock(), runtime, token, admin) }
                for (provider in runtime.feeds.configs) runtime.feeds.refresh(provider)
                Setup(dir, policyFile, runtime, fixture).block()
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }

    private fun HttpRequestBuilder.asAdmin() { bearerAuth(admin); contentType(ContentType.Application.Json) }

    private fun stationsIn(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject["stations"]?.jsonObject ?: JsonObject(emptyMap())

    private fun assignedIn(file: Path): Set<String> =
        Json.parseToJsonElement(Files.readString(file)).jsonObject["numbering"]?.jsonObject?.get("assigned")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

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
            assertTrue(entries.all { it.createdBy == "policy" })
            assertEquals(listOf("fixture", "alt", "twin"), entries.first().offeringIds.map { runtime.feeds.offering(it)!!.providerId })

            assertEquals(setOf("station", "ts station", "twin sports"), runtime.store.stationAvailability().keys)
            val changes = Json.decodeFromString<List<PolicyChangeEntry>>(client.get("/api/v1/admin/policy/changes?limit=100") { asAdmin() }.bodyAsText())
            assertTrue(changes.all { it.change.reason == "api" })
            assertEquals(setOf("station", "ts station", "twin sports"), changes.filter { it.change.field == "station" && it.change.before == null }.map { it.change.key }.toSet())
            assertEquals(legacy, changes.filter { it.change.field == "station" && it.change.after == null }.map { it.change.entryId }.toSet(), "every adopted entry is logged as removed")

            val written = stationsIn(policyFile)
            assertEquals(mapOf("station" to "5", "ts station" to "6", "twin sports" to "12"), written.mapValues { it.value.jsonObject["number"]!!.jsonPrimitive.content })
            assertEquals(hash(Files.readString(policyFile)), runtime.policy!!.document().revision)
            assertEquals(first.revision, runtime.policy.document().revision)

            val second = Json.decodeFromString<PolicyApplyResult>(client.post("/api/v1/admin/policy/apply") { asAdmin() }.bodyAsText())
            assertFalse(second.changed); assertEquals(0, second.changes); assertTrue(second.assigned.isEmpty())
            assertEquals(first.revision, second.revision, "nothing new to write back")

            // Without the written numbers this policy would number from the floor; the file's numbers hold.
            val revision = runtime.policy.document().revision
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

    @Test fun `routes that edit the lineup by hand answer 410 under a policy`() = testApplication {
        withPolicy {
            for ((method, path) in listOf(
                HttpMethod.Post to "/api/v1/admin/lineup", HttpMethod.Put to "/api/v1/admin/lineup/order", HttpMethod.Post to "/api/v1/admin/lineup/adopt",
            )) {
                val response = client.request(path) { this.method = method; asAdmin(); setBody("{}") }
                assertEquals(HttpStatusCode.Gone, response.status, "$method $path")
                val error = Json.decodeFromString<ApiError>(response.bodyAsText())
                assertEquals("POLICY_MANAGED", error.code); assertTrue(error.message.contains("/api/v1/admin/policy"))
            }
            assertEquals("[]", client.get("/api/v1/admin/policy/changes") { asAdmin() }.bodyAsText())
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

    @Test fun `a station waits for a verified source, and the probe plan ranks its first source`() = testApplication {
        withPolicy("""{"providers":[{"id":"fixture"},{"id":"alt"}]}""", feeds = { listOf(it.feed, it.altFeed) }) {
            val first = runtime.policy!!.apply("test")
            assertEquals(0, first.enabledStations)
            val entry = runtime.lineup.entries().single()
            assertFalse(entry.enabled)
            val (lead, other) = entry.offeringIds
            assertEquals(mapOf(lead to 0, other to 1), runtime.policy.probeTargets())

            val at = MutableClock().now
            runtime.store.recordObservation(other, Observation("qualified", at, true), at)
            assertEquals(1, runtime.policy.apply("health").enabledStations)
            assertTrue(runtime.lineup.entries().single().enabled)
            assertEquals(at, runtime.store.stationAvailability().getValue("station").verifiedAt)
            assertEquals(mapOf(lead to 2, other to 2), runtime.policy.probeTargets())
        }
    }

    @Test fun `matching schedules teach an alias that is written back once, and different schedules never merge`() = testApplication {
        withPolicy("""{"health":{"requireVerified":false},"providers":[{"id":"learn"},{"id":"learn2"}]}""", feeds = { listOf(it.learnFeed, it.learnTwinFeed) }) {
            for (guide in runtime.guides.configs) runtime.guides.refresh(guide)
            val first = runtime.policy!!.apply("test")
            assertTrue(first.learned.isEmpty(), "no programmes are kept before the lineup maps the guides")
            assertEquals(listOf("learnshowtv: possible alias of learnshow", "nflnetwork: possible alias of nflchannel"), first.suggestedAliases)
            assertEquals(setOf("learnshow", "learnshowtv", "nflnetwork", "nflchannel"), stationsIn(policyFile).keys)

            for (guide in runtime.guides.configs) runtime.guides.refresh(guide)
            val preview = Json.decodeFromString<PolicyPreview>(client.get("/api/v1/admin/policy/preview") { asAdmin() }.bodyAsText())
            assertEquals(mapOf("learnshowtv" to "learnshow"), preview.learned)
            val second = runtime.policy.apply("test")
            assertEquals(mapOf("learnshowtv" to "learnshow"), second.learned)
            assertTrue(second.suggestedAliases.isEmpty(), "${second.suggestedAliases}")
            val written = Json.parseToJsonElement(Files.readString(policyFile)).jsonObject
            assertEquals(JsonObject(mapOf("learnshowtv" to JsonPrimitive("learnshow"))), written["names"]!!.jsonObject["learned"])
            assertEquals(setOf("learnshow", "nflnetwork", "nflchannel"), stationsIn(policyFile).keys, "the merged-away station's number-only override goes")
            val entries = runtime.lineup.entries().associateBy { it.number }
            assertEquals(listOf("30", "31", "41"), entries.keys.toList())
            assertEquals(listOf("learn", "learn2"), entries.getValue("30").offeringIds.map { runtime.feeds.offering(it)!!.providerId })

            val third = runtime.policy.apply("test")
            assertTrue(third.learned.isEmpty()); assertFalse(third.changed)
            assertEquals(second.revision, third.revision, "nothing new to write back")
        }
    }

    @Test fun `an unverified station's floor number is never written, and its block number is written once it verifies`() = testApplication {
        withPolicy("""{"providers":[{"id":"fixture"},{"id":"alt"}]}""", feeds = { listOf(it.feed, it.altFeed) }) {
            val before = Files.readString(policyFile)
            val first = runtime.policy!!.apply("test")
            assertEquals(0, first.enabledStations)
            assertTrue(first.assigned.isEmpty())
            assertEquals("1000", runtime.lineup.entries().single().number)
            assertEquals(before, Files.readString(policyFile), "nothing is written for an unverified station")

            val at = MutableClock().now
            runtime.store.recordObservation(runtime.lineup.entries().single().offeringIds.last(), Observation("qualified", at, true), at)
            val second = runtime.policy.apply("health")
            assertEquals(1, second.enabledStations)
            assertEquals(mapOf("station" to "5"), second.assigned)
            assertEquals(mapOf("station" to "5"), stationsIn(policyFile).mapValues { it.value.jsonObject["number"]!!.jsonPrimitive.content })
            assertEquals(setOf("station"), assignedIn(policyFile))

            val third = runtime.policy.apply("health")
            assertTrue(third.assigned.isEmpty())
            assertEquals(second.revision, third.revision, "nothing new to write back")
        }
    }

    @Test fun `a server-assigned number leaves the file once its station has been gone for keepMissingDays, a human one stays`() = testApplication {
        val long = MutableClock().now.minus(Duration.ofDays(8))
        val policy = """{"names":{"version":1},"providers":[{"id":"fixture"},{"id":"alt"},{"id":"twin"},{"id":"ts"}],"health":{"requireVerified":false},"numbering":{"floor":100,"keepProviderNumbers":false,"assigned":["gone"]},"stations":{"gone":{"number":"50"},"human":{"number":"51"}}}"""
        withPolicy(policy) {
            runtime.store.saveStationAvailability(
                mapOf(
                    "gone" to StationAvailability(lastNumber = "50", lastName = "Gone", missingSince = long),
                    "human" to StationAvailability(lastNumber = "51", lastName = "Human", missingSince = long),
                ),
                long,
            )
            val applied = runtime.policy!!.apply("test")
            assertFalse("gone" in stationsIn(policyFile), "the reclaimed station's entry goes once its number does")
            assertEquals("51", stationsIn(policyFile).getValue("human").jsonObject["number"]!!.jsonPrimitive.content)
            assertEquals(setOf("station", "ts station", "twin sports"), assignedIn(policyFile))
            assertFalse("gone" in applied.assigned)
            assertTrue(runtime.lineup.entries().none { it.number == "50" }, "the lineup holds no reclaimed number")
        }
    }

    @Test fun `a PATCH that sets a number takes the key out of the assigned list, so later runs keep it`() = testApplication {
        withPolicy {
            runtime.policy!!.apply("test")
            assertEquals(setOf("station", "ts station", "twin sports"), assignedIn(policyFile))

            val patched = client.patch("/api/v1/admin/lineup/${stationId("station")}") { asAdmin(); setBody("""{"number":"13"}""") }
            assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
            assertEquals(setOf("ts station", "twin sports"), assignedIn(policyFile))
            assertEquals("13", stationsIn(policyFile).getValue("station").jsonObject["number"]!!.jsonPrimitive.content)

            runtime.policy.apply("test")
            assertEquals("13", runtime.lineup.entry(stationId("station"))!!.number)
            assertEquals(setOf("ts station", "twin sports"), assignedIn(policyFile))
        }
    }

    @Test fun `a policy PUT that changes a server-assigned number takes the key out of the assigned list`() = testApplication {
        withPolicy {
            runtime.policy!!.apply("test")
            val before = Json.parseToJsonElement(Files.readString(policyFile)).jsonObject
            val revision = runtime.policy.document().revision
            val stations = before.getValue("stations").jsonObject.toMutableMap()
            stations["ts station"] = JsonObject(stations.getValue("ts station").jsonObject + ("number" to JsonPrimitive("77")))
            val edited = JsonObject(before + ("stations" to JsonObject(stations))).toString()
            val put = client.put("/api/v1/admin/policy") { asAdmin(); header(HttpHeaders.IfMatch, "\"$revision\""); setBody(edited) }
            assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
            assertEquals(setOf("station", "twin sports"), assignedIn(policyFile), "the changed number is a person's now; the unchanged ones stay the server's")
        }
    }

    @Test fun `with followGroup a server-numbered station moves into its group's block and the file follows, a hand-set one stays`() = testApplication {
        val policy = """{"names":{"version":1},"providers":[{"id":"fixture"},{"id":"alt"},{"id":"twin"},{"id":"ts"}],"health":{"requireVerified":false},""" +
            """"numbering":{"floor":1000,"groups":{"News":10,"Sports":500},"followGroup":true,"assigned":["station"]},""" +
            """"stations":{"station":{"number":"501","group":"News"},"ts station":{"number":"502","group":"News"}}}"""
        withPolicy(policy) {
            val applied = runtime.policy!!.apply("test")
            assertEquals("10", applied.assigned["station"])
            assertEquals("10", runtime.lineup.entry(stationId("station"))!!.number)
            assertEquals("10", stationsIn(policyFile).getValue("station").jsonObject["number"]!!.jsonPrimitive.content)
            assertEquals("502", runtime.lineup.entry(stationId("ts station"))!!.number, "a number set by hand never moves")
            assertTrue("station" in assignedIn(policyFile))

            val again = runtime.policy.apply("test")
            assertFalse("station" in again.assigned, "once in its block, the station stays put")
            assertEquals("10", runtime.lineup.entry(stationId("station"))!!.number)
        }
    }
}

class PolicyLoopTest {
    private val json = Json { encodeDefaults = true }
    @Test fun `with a policy, a feed import triggers a policy run`() = kotlinx.coroutines.runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-policy-loop-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(listOf(fixture.feed))},"guides":[]}""")
            val policyFile = dir.resolve("policy.json")
            Files.writeString(policyFile, """{"providers":[{"id":"fixture"}]}""")
            val store = openStore(dir)
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, "secret".repeat(8), allowLoopbackUpstreams = true, policyFile = policyFile)
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 10, probes = false)
            try {
                runtime.feeds.refresh(fixture.feed)
                pollUntil(10_000) { runtime.lineup.entries().isNotEmpty() }
                assertEquals(listOf(stationId("station")), runtime.lineup.entries().map { it.id })
                assertEquals("policy", runtime.lineup.entries().single().createdBy)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}
