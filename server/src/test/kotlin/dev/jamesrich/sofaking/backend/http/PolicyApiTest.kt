package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.test.*

class PolicyApiTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `the policy, its preview and a station's candidates are served read-only`() = testApplication {
        val dir = Files.createTempDirectory("sofa-policy-api-")
        val token = "secret".repeat(8)
        val admin = "curator".repeat(6)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(listOf(fixture.feed, fixture.altFeed))},"guides":[]}""")
            val policyFile = dir.resolve("policy.json")
            val policyText = """{"providers":[{"id":"alt"},{"id":"fixture"}],"health":{"requireVerified":false},"numbering":{"floor":100},"stations":{"station":{"group":"Local"}}}"""
            Files.writeString(policyFile, policyText)
            val store = openStore(dir)
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin, policyFile = policyFile)
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000, probes = false)
            try {
                for (provider in runtime.feeds.configs) runtime.feeds.refresh(provider)
                runtime.adoptAll()
                val before = runtime.lineup.entries()
                assertEquals(2, before.size, "adoption makes one entry per provider")
                application { backendModule(store, MutableClock(), runtime, token, admin) }
                suspend fun HttpRequestBuilder.asAdmin() { bearerAuth(admin) }

                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/policy") { bearerAuth(token) }.status)
                val document = Json.decodeFromString<PolicyDocument>(client.get("/api/v1/admin/policy") { asAdmin() }.bodyAsText())
                assertEquals(hash(policyText), document.revision)
                assertEquals(listOf("alt", "fixture"), document.policy.providers.map { it.id })

                val preview = Json.decodeFromString<PolicyPreview>(client.get("/api/v1/admin/policy/preview") { asAdmin() }.bodyAsText())
                assertEquals(1, preview.stations); assertEquals(1, preview.enabledStations)
                assertEquals(listOf(PolicyRegrouping(listOf("station"), before.map { it.id }.sorted())), preview.merges)
                assertTrue(preview.added.isEmpty()); assertTrue(preview.removed.isEmpty()); assertTrue(preview.splits.isEmpty())
                assertEquals(1, preview.counts["merges"])
                assertEquals(document.revision, preview.revision)
                assertFalse(client.get("/api/v1/admin/policy/preview") { asAdmin() }.bodyAsText().contains("/alt.m3u8"), "the preview never carries stream URLs")
                assertEquals(before, runtime.lineup.entries(), "a preview writes nothing")

                val explained = Json.decodeFromString<StationExplanation>(client.get("/api/v1/admin/stations/station") { asAdmin() }.bodyAsText())
                assertEquals("Local", explained.station?.group)
                assertEquals("5", explained.station?.number, "the provider number is free")
                val alt = runtime.feeds.channels("alt").single()
                assertEquals(listOf(runtime.feeds.offeringsOf(alt.id).single().id, runtime.feeds.offeringsOf(runtime.feeds.channels("fixture").single().id).single().id), explained.station?.offeringIds)
                assertEquals(listOf("fallback source", "leading candidate"), explained.candidates.map { it.reason }.sorted())
                assertEquals("leading candidate", explained.candidates.single { it.providerId == "alt" }.reason)
                val unknown = Json.decodeFromString<StationExplanation>(client.get("/api/v1/admin/stations/${"no such station".encodeURLPathPart()}") { asAdmin() }.bodyAsText())
                assertNull(unknown.station); assertTrue(unknown.candidates.isEmpty())
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `without SOFA_POLICY the routes answer 404, and an invalid policy stops the start`() = testApplication {
        val dir = Files.createTempDirectory("sofa-policy-off-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(listOf(fixture.feed))},"guides":[]}""")
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true), MutableClock())
            try {
                assertNotNull(runtime.curationLoop, "without a policy curation runs as before")
                application { backendModule(store, MutableClock(), runtime, token) }
                val response = client.get("/api/v1/admin/policy/preview") { bearerAuth(token) }
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertTrue(response.bodyAsText().contains("POLICY_NOT_CONFIGURED"))

                val invalid = dir.resolve("bad-policy.json")
                Files.writeString(invalid, """{"providers":[{"id":"nowhere"}],"maxSourcesPerStation":0}""")
                val failure = assertFailsWith<IllegalArgumentException> {
                    LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, policyFile = invalid), MutableClock())
                }
                assertTrue(failure.message!!.contains("unknown provider nowhere") && failure.message!!.contains("maxSourcesPerStation"), failure.message)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}
