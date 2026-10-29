package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.PolicyDocument
import dev.jamesrich.sofaking.backend.api.PolicyPreview
import dev.jamesrich.sofaking.backend.api.PolicyRegrouping
import dev.jamesrich.sofaking.backend.api.StationExplanation
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.hash
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyApiTest {
    private val json = Json { encodeDefaults = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)
    private val policyText = """{"providers":[{"id":"alt"},{"id":"fixture"}],"health":{"requireVerified":false},""" +
        """"numbering":{"floor":100},"stations":{"station":{"group":"Local"}}}"""

    @Test fun `the policy document is served to the admin token with its revision`() = policyApi {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/policy") { bearerAuth(token) }.status)
        val document = admin<PolicyDocument>("/api/v1/admin/policy")
        assertEquals(hash(policyText), document.revision)
        assertEquals(listOf("alt", "fixture"), document.policy.providers.map { it.id })
    }

    @Test fun `the preview shows the merge the policy makes and writes nothing`() = policyApi {
        val before = runtime.lineup.entries()
        val preview = admin<PolicyPreview>("/api/v1/admin/policy/preview")
        assertEquals(1, preview.stations)
        assertEquals(1, preview.enabledStations)
        assertEquals(listOf(PolicyRegrouping(listOf("station"), before.map { it.id }.sorted())), preview.merges)
        assertTrue(preview.added.isEmpty())
        assertTrue(preview.removed.isEmpty())
        assertTrue(preview.splits.isEmpty())
        assertEquals(1, preview.counts["merges"])
        assertEquals(hash(policyText), preview.revision)
        assertFalse(
            client.get("/api/v1/admin/policy/preview") { bearerAuth(admin) }.bodyAsText().contains("/alt.m3u8"),
            "the preview never carries stream URLs",
        )
        assertEquals(before, runtime.lineup.entries(), "a preview writes nothing")
    }

    @Test fun `a station's explanation lists its sources in order and why each candidate is there`() = policyApi {
        val explained = admin<StationExplanation>("/api/v1/admin/stations/station")
        assertEquals("Local", explained.station?.group)
        assertEquals("5", explained.station?.number, "the provider number is free")
        val published = runtime.feeds.published
        val offerings = listOf("alt", "fixture").map { provider ->
            published.offeringsOf(published.channels(provider).single().id).single().id
        }
        assertEquals(offerings, explained.station?.offeringIds)
        assertEquals(listOf("fallback source", "leading candidate"), explained.candidates.map { it.reason }.sorted())
        assertEquals("leading candidate", explained.candidates.single { it.providerId == "alt" }.reason)
    }

    @Test fun `an unknown station explains as no station and no candidates`() = policyApi {
        val unknown = admin<StationExplanation>("/api/v1/admin/stations/${"no such station".encodeURLPathPart()}")
        assertNull(unknown.station)
        assertTrue(unknown.candidates.isEmpty())
    }

    /** A policy leading with the alt feed over the fixture's, both adopted into the lineup, one entry each. */
    private inner class PolicyApi(val app: ApplicationTestBuilder, val runtime: LiveRuntime) {
        val client get() = app.client

        suspend inline fun <reified T> admin(path: String): T =
            Json.decodeFromString(client.get(path) { bearerAuth(admin) }.bodyAsText())
    }

    private fun policyApi(block: suspend PolicyApi.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-policy-api-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(
                sources,
                """{"providers":${json.encodeToString(listOf(fixture.feed, fixture.altFeed))},"guides":[]}""",
            )
            val policyFile = dir.resolve("policy.json")
            Files.writeString(policyFile, policyText)
            val store = openStore(dir)
            val config =
                ServerConfig(
                    "127.0.0.1",
                    8080,
                    dir,
                    sources,
                    token,
                    allowLoopbackUpstreams = true,
                    adminToken = admin,
                    policyFile = policyFile,
                )
            val runtime =
                LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000, probes = false)
            try {
                for (provider in runtime.feeds.configs) runtime.feeds.refresh(provider)
                runtime.adoptAll()
                assertEquals(2, runtime.lineup.entries().size, "adoption makes one entry per provider")
                application { backendModule(store, MutableClock(), runtime, token, admin) }
                PolicyApi(this, runtime).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `without SOFA_POLICY the routes answer 404, and an invalid policy stops the start`() = testApplication {
        val dir = Files.createTempDirectory("sofa-policy-off-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(listOf(fixture.feed))},"guides":[]}""")
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true),
                MutableClock(),
            )
            try {
                assertNull(runtime.policy)
                runtime.feeds.refresh(fixture.feed)
                assertTrue(runtime.lineup.entries().isEmpty(), "without a policy nothing enters the lineup on its own")
                application { backendModule(store, MutableClock(), runtime, token) }
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/api/v1/admin/policy/changes") {
                        bearerAuth(token)
                    }.status,
                )
                val response = client.get("/api/v1/admin/policy/preview") { bearerAuth(token) }
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertTrue(response.bodyAsText().contains("POLICY_NOT_CONFIGURED"))

                val invalid = dir.resolve("bad-policy.json")
                Files.writeString(invalid, """{"providers":[{"id":"nowhere"}],"maxSourcesPerStation":0}""")
                val failure = assertFailsWith<IllegalArgumentException> {
                    LiveRuntime.create(
                        store,
                        ServerConfig(
                            "127.0.0.1",
                            8080,
                            dir,
                            sources,
                            token,
                            allowLoopbackUpstreams = true,
                            policyFile = invalid,
                        ),
                        MutableClock(),
                    )
                }
                assertTrue(
                    failure.message!!.contains("unknown provider nowhere") &&
                        failure.message!!.contains("maxSourcesPerStation"),
                    failure.message,
                )
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
}
