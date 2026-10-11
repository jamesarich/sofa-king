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

class CurationApiTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `curation settings, rules, preview, run, import and changes are served behind the admin token`() = testApplication {
        val dir = Files.createTempDirectory("sofa-curation-api-")
        val token = "secret".repeat(8)
        val admin = "curator".repeat(6)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(listOf(fixture.feed, fixture.altFeed, fixture.twinFeed))},"guides":[]}""")
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin), MutableClock())
            try {
                for (config in runtime.feeds.configs) runtime.feeds.refresh(config)
                application { backendModule(store, MutableClock(), runtime, token, admin) }
                suspend fun HttpRequestBuilder.asAdmin() { bearerAuth(admin); contentType(ContentType.Application.Json) }

                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/curation/settings") { bearerAuth(token) }.status)
                val settings = Json.decodeFromString<CurationSettings>(client.get("/api/v1/admin/curation/settings") { asAdmin() }.bodyAsText())
                assertFalse(settings.enabled); assertEquals("healthy", settings.enableOn)
                assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/admin/curation/settings") { asAdmin(); setBody("""{"enableOn":"never"}""") }.status)
                val updated = Json.decodeFromString<CurationSettings>(client.put("/api/v1/admin/curation/settings") { asAdmin(); setBody("""{"retiredLogoHosts":["Old.Host:9192"],"disableAfterHours":12}""") }.bodyAsText())
                assertEquals(listOf("old.host:9192"), updated.retiredLogoHosts); assertEquals(12, updated.disableAfterHours); assertFalse(updated.enabled)

                val candidate = Json.decodeFromString<CandidatePage>(client.get("/api/v1/admin/candidates?provider=fixture") { asAdmin() }.bodyAsText()).items.single()
                val entry = Json.decodeFromString<LineupEntry>(client.post("/api/v1/admin/lineup") { asAdmin(); setBody(json.encodeToString(CreateLineupEntry(channelId = candidate.channelId))) }.bodyAsText())
                assertEquals("News", entry.group)

                assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/admin/curation/rules") { asAdmin(); setBody("""{"kind":"group"}""") }.status)
                val created = client.post("/api/v1/admin/curation/rules") { asAdmin(); setBody("""{"kind":"group","match":{"group":"news"},"set":{"group":"Headlines"},"note":"from the lookup table"}""") }
                assertEquals(HttpStatusCode.Created, created.status)
                val rule = Json.decodeFromString<CurationRule>(created.bodyAsText())
                assertTrue(rule.id.isNotBlank()); assertEquals("Headlines", rule.set.group)

                val preview = Json.decodeFromString<CurationPlan>(client.get("/api/v1/admin/curation/preview") { asAdmin() }.bodyAsText())
                assertEquals(mapOf("attach" to 1, "regroup" to 1), preview.counts); assertFalse(preview.enabled)
                assertEquals("News", Json.decodeFromString<LineupEntry>(client.get("/api/v1/admin/lineup/${entry.id}") { asAdmin() }.bodyAsText()).group, "a preview changes nothing")
                assertEquals(0, Json.decodeFromString<List<CurationChange>>(client.get("/api/v1/admin/curation/changes") { asAdmin() }.bodyAsText()).size)

                val run = Json.decodeFromString<CurationPlan>(client.post("/api/v1/admin/curation/run") { asAdmin() }.bodyAsText())
                assertTrue(run.enabled); assertEquals(2, run.actions.size)
                val after = Json.decodeFromString<LineupEntry>(client.get("/api/v1/admin/lineup/${entry.id}") { asAdmin() }.bodyAsText())
                assertEquals("Headlines", after.group); assertEquals(listOf("fixture", "alt"), after.offerings.map { it.providerId })
                assertTrue(after.pinned.isEmpty(), "automation pins nothing")
                val changes = Json.decodeFromString<List<CurationChange>>(client.get("/api/v1/admin/curation/changes?limit=10") { asAdmin() }.bodyAsText())
                assertEquals(setOf("attach", "regroup"), changes.map { it.action.kind }.toSet())
                assertTrue(changes.all { it.action.entryId == entry.id })

                val renamed = client.put("/api/v1/admin/curation/rules/${rule.id}") { asAdmin(); setBody("""{"kind":"group","match":{"group":"news"},"set":{"group":"Elsewhere"}}""") }
                assertEquals(HttpStatusCode.OK, renamed.status)
                assertEquals(HttpStatusCode.NotFound, client.put("/api/v1/admin/curation/rules/missing") { asAdmin(); setBody("""{"kind":"group","match":{"group":"news"},"set":{"group":"x"}}""") }.status)
                assertEquals(1, Json.decodeFromString<List<CurationRule>>(client.get("/api/v1/admin/curation/rules") { asAdmin() }.bodyAsText()).size)

                // The pipeline's records carry fields the rules ignore; the import reads them anyway.
                val imported = client.post("/api/v1/admin/curation/import") {
                    asAdmin()
                    setBody("""{"groupMap":{"us: sports":"Sports"},"sportsCorrections":[{"id":8219,"beforeOverride":{"name":null,"logo_id":null},"beforeName":"Ball Network","afterName":"Ball Channel"},{"splitPaid":1,"freeChannel":2}],"corrections":{"relabelled":1,"correctGuides":{"1":2}}}""")
                }
                assertEquals(HttpStatusCode.OK, imported.status)
                val report = Json.decodeFromString<CurationImportReport>(imported.bodyAsText())
                assertEquals(2, report.created); assertEquals(2, report.items.count { it.outcome == "applied-by-import" }); assertFalse(report.dryRun)
                assertEquals(3, Json.decodeFromString<List<CurationRule>>(client.get("/api/v1/admin/curation/rules") { asAdmin() }.bodyAsText()).size)

                assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/admin/curation/rules/${rule.id}") { asAdmin() }.status)
                assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/admin/curation/rules/${rule.id}") { asAdmin() }.status)
                assertFalse(client.get("/api/v1/admin/curation/preview") { asAdmin() }.bodyAsText().contains(fixture.base), "the plan never carries stream URLs")
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}
