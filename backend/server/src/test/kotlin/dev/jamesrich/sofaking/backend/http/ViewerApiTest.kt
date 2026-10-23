package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.PairingCode
import dev.jamesrich.sofaking.backend.api.Viewer
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
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

    @Test fun `a pairing code opens the API once and a client keeps its preferences on the server`() = testApplication {
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
                val bootstrap = Json.decodeFromString<Bootstrap>(
                    client.get("/api/v1/bootstrap") {
                        bearerAuth(token)
                    }.bodyAsText(),
                )
                assertTrue(bootstrap.capabilities.viewers)

                // No code is live: a claim is refused, with no token leaking, and needs no bearer token to be heard.
                val cold = client.post("/api/v1/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(PairingClaim("123456", "tv-1")))
                }
                assertEquals(HttpStatusCode.Forbidden, cold.status)
                assertEquals(Wire.ErrorCode.PAIRING_INVALID, Json.decodeFromString<ApiError>(cold.bodyAsText()).code)
                assertFalse(cold.bodyAsText().contains(token))

                // The admin API issues a code; the API token does not open that route.
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.post("/api/v1/admin/pairing-codes") {
                        bearerAuth(token)
                    }.status,
                )
                val issued = client.post("/api/v1/admin/pairing-codes") { bearerAuth(admin) }
                assertEquals(HttpStatusCode.Created, issued.status)
                val code = Json.decodeFromString<PairingCode>(issued.bodyAsText())
                assertEquals(6, code.code.length)
                assertEquals(clock.now.plusSeconds(600).toString(), code.expiresAt)

                val wrong = if (code.code == "000000") "111111" else "000000"
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.post("/api/v1/pair") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(PairingClaim(wrong)))
                    }.status,
                )
                val paired = client.post("/api/v1/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(PairingClaim(code.code, "tv-1", "Living room", "android-tv")))
                }
                assertEquals(HttpStatusCode.OK, paired.status)
                assertKeepsWireFixture("v1/pairing-result.json", paired.bodyAsText())
                val result = Json.decodeFromString<PairingResult>(paired.bodyAsText())
                assertEquals(token, result.apiToken)
                assertEquals(store.serverId(), result.serverId)
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.post("/api/v1/pair") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(PairingClaim(code.code)))
                    }.status,
                    "a code works once",
                )

                // The paired device is listed, without preferences until it saves some.
                val listed = Json.decodeFromString<List<Viewer>>(
                    client.get("/api/v1/admin/viewers") {
                        bearerAuth(admin)
                    }.bodyAsText(),
                )
                assertEquals(listOf("tv-1"), listed.map { it.clientId })
                assertEquals("Living room", listed.single().name)
                assertEquals("android-tv", listed.single().platform)
                assertNull(listed.single().preferences)
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/api/v1/preferences/tv-1") {
                        bearerAuth(result.apiToken)
                    }.status,
                )

                // Preferences need the token, are stamped by the server, and read back as saved.
                val body =
                    ViewerPreferences(
                        hidden = listOf("h1", "h1"),
                        favorites = listOf("f2", "f1"),
                        recent = listOf("f1"),
                        name = "Living room TV",
                    )
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.put("/api/v1/preferences/tv-1") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(body))
                    }.status,
                )
                clock.now = clock.now.plusSeconds(60)
                val saved = client.put("/api/v1/preferences/tv-1") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(body))
                }
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
                        client.get("/api/v1/preferences/tv-1") {
                            bearerAuth(token)
                        }.bodyAsText(),
                    ),
                )
                assertEquals(
                    1,
                    Json.decodeFromString<List<Viewer>>(
                        client.get("/api/v1/admin/viewers") {
                            bearerAuth(admin)
                        }.bodyAsText(),
                    ).single().preferences?.hidden?.size,
                )

                // A device that never paired appears once it saves, newest first; bad ids and oversized lists are refused.
                clock.now = clock.now.plusSeconds(60)
                assertEquals(
                    HttpStatusCode.OK,
                    client.put("/api/v1/preferences/phone.2") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }.status,
                )
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.put("/api/v1/preferences/bad%20id") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }.status,
                )
                val tooMany = """{"hidden":[${(1..1001).joinToString(",") { "\"c$it\"" }}]}"""
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.put("/api/v1/preferences/tv-1") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(tooMany)
                    }.status,
                )
                assertEquals(
                    listOf("phone.2", "tv-1"),
                    Json.decodeFromString<List<Viewer>>(
                        client.get("/api/v1/admin/viewers") {
                            bearerAuth(admin)
                        }.bodyAsText(),
                    ).map { it.clientId },
                )

                // Forgetting drops the record; the next save brings it back.
                assertEquals(
                    HttpStatusCode.NoContent,
                    client.delete("/api/v1/admin/viewers/tv-1") {
                        bearerAuth(admin)
                    }.status,
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.delete("/api/v1/admin/viewers/tv-1") {
                        bearerAuth(admin)
                    }.status,
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/api/v1/preferences/tv-1") {
                        bearerAuth(token)
                    }.status,
                )
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
