package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.PairingCode
import dev.jamesrich.sofaking.backend.api.Viewer
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Bootstrap
import dev.jamesrich.sofaking.contract.PairingClaim
import dev.jamesrich.sofaking.contract.PairingResult
import dev.jamesrich.sofaking.contract.ViewerPreferences
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerApiTest {
    private val json = Json { encodeDefaults = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)

    @Test fun `the bootstrap says the server keeps viewers`() = viewers {
        val response = client.get("/api/v1/bootstrap") { bearerAuth(token) }
        assertTrue(Json.decodeFromString<Bootstrap>(response.bodyAsText()).capabilities.viewers)
    }

    @Test fun `with no live code a claim is refused, needing no bearer and leaking no token`() = viewers {
        val cold = claim(PairingClaim("123456", "tv-1"))
        assertEquals(HttpStatusCode.Forbidden, cold.status)
        assertEquals(Wire.ErrorCode.PAIRING_INVALID, Json.decodeFromString<ApiError>(cold.bodyAsText()).code)
        assertFalse(cold.bodyAsText().contains(token))
    }

    @Test fun `the admin token issues a six-digit code for ten minutes, and the API token cannot`() = viewers {
        val withApiToken = client.post("/api/v1/admin/pairing-codes") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, withApiToken.status)
        val code = issue()
        assertEquals(6, code.code.length)
        assertEquals(clock.now.plusSeconds(600).toString(), code.expiresAt)
    }

    @Test fun `a pairing code hands out the API token once, and a wrong code nothing`() = viewers {
        val code = issue()
        val wrong = if (code.code == "000000") "111111" else "000000"
        assertEquals(HttpStatusCode.Forbidden, claim(PairingClaim(wrong)).status)
        val paired = claim(PairingClaim(code.code, "tv-1", "Living room", "android-tv"))
        assertEquals(HttpStatusCode.OK, paired.status)
        assertKeepsWireFixture("v1/pairing-result.json", paired.bodyAsText())
        val result = Json.decodeFromString<PairingResult>(paired.bodyAsText())
        assertEquals(token, result.apiToken)
        assertEquals(store.serverId(), result.serverId)
        assertEquals(HttpStatusCode.Forbidden, claim(PairingClaim(code.code)).status, "a code works once")
    }

    @Test fun `a paired device is listed without preferences until it saves some`() = viewers {
        pair()
        val listed = list()
        assertEquals(listOf("tv-1"), listed.map { it.clientId })
        assertEquals("Living room", listed.single().name)
        assertEquals("android-tv", listed.single().platform)
        assertNull(listed.single().preferences)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/preferences/tv-1") { bearerAuth(token) }.status)
    }

    @Test fun `preferences need the token, are stamped by the server and read back as saved`() = viewers {
        pair()
        val body = ViewerPreferences(
            hidden = listOf("h1", "h1"),
            favorites = listOf("f2", "f1"),
            recent = listOf("f1"),
            name = "Living room TV",
        )
        assertEquals(HttpStatusCode.Unauthorized, save("tv-1", json.encodeToString(body), bearer = null).status)
        clock.now = clock.now.plusSeconds(60)
        val saved = save("tv-1", json.encodeToString(body))
        assertEquals(HttpStatusCode.OK, saved.status)
        assertKeepsWireFixture("v1/viewer-preferences.json", saved.bodyAsText())
        val stored = Json.decodeFromString<ViewerPreferences>(saved.bodyAsText())
        assertEquals(listOf("h1"), stored.hidden)
        assertEquals(listOf("f2", "f1"), stored.favorites)
        assertEquals("Living room TV", stored.name)
        assertEquals("android-tv", stored.platform, "an empty label keeps the stored one")
        assertEquals(clock.now.toString(), stored.updatedAt)
        assertEquals(
            stored,
            Json.decodeFromString<ViewerPreferences>(
                client.get("/api/v1/preferences/tv-1") { bearerAuth(token) }.bodyAsText(),
            ),
        )
        assertEquals(1, list().single().preferences?.hidden?.size)
    }

    @Test fun `a device that never paired is listed once it saves, newest first`() = viewers {
        pair()
        save("tv-1", "{}")
        clock.now = clock.now.plusSeconds(60)
        assertEquals(HttpStatusCode.OK, save("phone.2", "{}").status)
        assertEquals(listOf("phone.2", "tv-1"), list().map { it.clientId })
    }

    @Test fun `a bad client id or an oversized list is refused`() = viewers {
        assertEquals(HttpStatusCode.BadRequest, save("bad%20id", "{}").status)
        val tooMany = """{"hidden":[${(1..1001).joinToString(",") { "\"c$it\"" }}]}"""
        assertEquals(HttpStatusCode.BadRequest, save("tv-1", tooMany).status)
    }

    @Test fun `forgetting a viewer drops its record and its preferences`() = viewers {
        pair()
        save("tv-1", "{}")
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/admin/viewers/tv-1") { bearerAuth(admin) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/admin/viewers/tv-1") { bearerAuth(admin) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/preferences/tv-1") { bearerAuth(token) }.status)
    }

    /** A server over the fixture's feed with an API and an admin token, on a clock the test moves. */
    private inner class Viewers(val app: ApplicationTestBuilder, val clock: MutableClock, val store: BackendStore) {
        val client get() = app.client

        suspend fun claim(claim: PairingClaim) = client.post("/api/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(claim))
        }

        suspend fun issue(): PairingCode {
            val issued = client.post("/api/v1/admin/pairing-codes") { bearerAuth(admin) }
            assertEquals(HttpStatusCode.Created, issued.status)
            return Json.decodeFromString(issued.bodyAsText())
        }

        /** Pairs the living room TV, tv-1. */
        suspend fun pair() {
            val claimed = claim(PairingClaim(issue().code, "tv-1", "Living room", "android-tv"))
            assertEquals(HttpStatusCode.OK, claimed.status)
        }

        suspend fun save(clientId: String, body: String, bearer: String? = token) =
            client.put("/api/v1/preferences/$clientId") {
                bearer?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        suspend fun list(): List<Viewer> =
            Json.decodeFromString(client.get("/api/v1/admin/viewers") { bearerAuth(admin) }.bodyAsText())
    }

    private fun viewers(block: suspend Viewers.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-viewers-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, json.encodeToString(listOf(fixture.feed)))
            val clock = MutableClock()
            val config =
                ServerConfig(
                    "127.0.0.1",
                    8080,
                    dir,
                    sources,
                    token,
                    allowLoopbackUpstreams = true,
                    adminToken = admin,
                    hdhr = HdhrConfig(enabled = false),
                )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, clock, probes = false)
            try {
                application { backendModule(store, clock, runtime, token, admin, config = config) }
                Viewers(this, clock, store).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `without an API token a claim hands out an empty one`() = testApplication {
        val dir = Files.createTempDirectory("sofa-viewers-open-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, json.encodeToString(listOf(fixture.feed)))
            val config =
                ServerConfig(
                    "127.0.0.1",
                    8080,
                    dir,
                    sources,
                    null,
                    allowLoopbackUpstreams = true,
                    adminToken = admin,
                    hdhr = HdhrConfig(enabled = false),
                )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
            try {
                application { backendModule(store, MutableClock(), runtime, null, admin, config = config) }
                val code = Json.decodeFromString<PairingCode>(
                    client.post("/api/v1/admin/pairing-codes") {
                        bearerAuth(admin)
                    }.bodyAsText(),
                )
                // A bad client id is refused before the code is spent.
                val badId = client.post("/api/v1/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(PairingClaim(code.code, "tv 1!")))
                }
                assertEquals(HttpStatusCode.BadRequest, badId.status)
                val paired = client.post("/api/v1/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(PairingClaim(code.code, "tv-1")))
                }
                assertEquals(HttpStatusCode.OK, paired.status)
                assertEquals("", Json.decodeFromString<PairingResult>(paired.bodyAsText()).apiToken)
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
}
