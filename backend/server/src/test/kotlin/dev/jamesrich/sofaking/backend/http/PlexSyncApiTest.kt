package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.PlexConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.PolicyFindings
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ApiError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.host
import io.ktor.client.request.port
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexSyncApiTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)
    private val plexToken = "plex-Secret-Token-42"
    private val policy = """{"names":{"version":1},"health":{"requireVerified":false},"providers":[{"id":"fixture"},{"id":"ts"}],"numbering":{"floor":100}}"""

    /** A Plex server holding device ABCDEF01 in DVR 10; records each request's method, URI and token header. */
    private class FakePlex : AutoCloseable {
        class Seen(val method: String, val uri: String, val token: String?)
        val seen = ConcurrentLinkedQueue<Seen>()
        private fun ApplicationCall.record() {
            seen +=
                Seen(request.local.method.value, request.local.uri, request.headers["X-Plex-Token"])
        }
        private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing {
                get("/livetv/dvrs") {
                    call.record()
                    call.respondText(
                        """{"MediaContainer":{"Dvr":[{"key":"10","lineup":"lineup://xmltv#Sofa","Device":[{"key":"20","uuid":"device://tv.plex.grabbers.hdhomerun/ABCDEF01","title":null}]}]}}""",
                        ContentType.Application.Json,
                    )
                }
                get("/livetv/epg/channelmap") {
                    call.record()
                    call.respondText(
                        """{"MediaContainer":{"ChannelMapping":[{"deviceIdentifier":"5","lineupIdentifier":"five","channelKey":"5-key"}]}}""",
                        ContentType.Application.Json,
                    )
                }
                put("/media/grabbers/devices/{key}/channelmap") {
                    call.record()
                    call.respond(HttpStatusCode.OK)
                }
                post("/livetv/dvrs/{key}/reloadGuide") {
                    call.record()
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
        val base: String

        init {
            server.start(wait = false)
            base =
                "http://127.0.0.1:${kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }}"
        }

        fun syncs() = seen.count { it.uri == "/livetv/dvrs" }

        override fun close() {
            server.stop(100, 2000)
        }
    }

    private suspend fun ApplicationTestBuilder.withRuntime(plex: FakePlex?, block: suspend (LiveRuntime) -> Unit) {
        val dir = Files.createTempDirectory("sofa-plex-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(
                sources,
                """{"providers":${json.encodeToString(listOf(fixture.feed, fixture.tsFeed))},"guides":[]}""",
            )
            val policyFile = Files.createDirectories(dir.resolve("policy")).resolve("policy.json")
            Files.writeString(policyFile, policy)
            val store = openStore(dir)
            val config = ServerConfig(
                "127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin,
                hdhr = HdhrConfig(deviceId = "ABCDEF01", extraPorts = listOf(8081)), policyFile = policyFile,
                plex = plex?.let { PlexConfig(it.base, plexToken) },
            )
            val runtime = LiveRuntime.create(
                store,
                config,
                MutableClock(),
                policyDebounceMillis = 3_600_000,
                probes = false,
                plexDebounceMillis = 200,
            )
            try {
                application { backendModule(store, MutableClock(), runtime, token, admin) }
                for (provider in runtime.feeds.configs) runtime.feeds.refresh(provider)
                block(runtime)
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `an apply that changes the lineup syncs Plex after the debounce, and the status and findings report it`() =
        testApplication {
            FakePlex().use { plex ->
                withRuntime(plex) { runtime ->
                    val sync = runtime.plex!!
                    pollUntil(10_000) { sync.status().lastCause == "startup" }
                    assertEquals(1, plex.syncs())

                    val applied = json.decodeFromString<PolicyApplyResult>(
                        client.post("/api/v1/admin/policy/apply") {
                            bearerAuth(admin)
                        }.bodyAsText(),
                    )
                    assertTrue(applied.changed)
                    // Idle: the policy sync has finished and the loop waits for the next request.
                    pollUntil(10_000) {
                        plex.syncs() == 2 && sync.status().lastCause == "policy" &&
                            runtime.jobs.snapshot().none { it.id == "plex" && it.running }
                    }

                    // A sync request of its own after the apply: had the apply asked for one, the loop would already be in that
                    // sync's debounce, swallow this request and sync with the cause "policy".
                    assertFalse(
                        json.decodeFromString<PolicyApplyResult>(
                            client.post("/api/v1/admin/policy/apply") {
                                bearerAuth(admin)
                            }.bodyAsText(),
                        ).changed,
                    )
                    val beforeSentinel = sync.status()
                    sync.requestSync("now")
                    pollUntil(10_000) { sync.status() !== beforeSentinel }
                    assertEquals("now", sync.status().lastCause, "an apply that changed nothing does not sync")
                    assertEquals(3, plex.syncs())

                    val second = hdhrDeviceId(HdhrConfig(deviceId = "ABCDEF01"), runtime.store.serverId(), 1)
                    val status = json.decodeFromString<PlexStatus>(
                        client.get("/api/v1/admin/plex") {
                            bearerAuth(admin)
                        }.bodyAsText(),
                    )
                    assertEquals(listOf("ABCDEF01" to 1), status.devices.map { it.deviceId to it.mapped })
                    assertEquals(listOf(second), status.missingDevices)
                    assertEquals(listOf("10"), status.dvrs)
                    val finding = "plex: device $second (device 1) is not added to Plex"
                    assertTrue(
                        finding in
                            json.decodeFromString<PolicyFindings>(
                                client.get("/api/v1/admin/policy/findings") {
                                    bearerAuth(admin)
                                }.bodyAsText(),
                            ).findings,
                    )

                    assertEquals(
                        HttpStatusCode.Unauthorized,
                        client.get("/api/v1/admin/plex") {
                            bearerAuth(token)
                        }.status,
                    )
                    val now = json.decodeFromString<PlexStatus>(
                        client.post("/api/v1/admin/plex/sync") {
                            bearerAuth(admin)
                        }.bodyAsText(),
                    )
                    assertEquals("api", now.lastCause)
                    assertEquals(4, plex.syncs())

                    val put = plex.seen.first { it.method == "PUT" }
                    assertTrue(put.uri.startsWith("/media/grabbers/devices/20/channelmap?"))
                    assertTrue(plex.seen.all { it.token == plexToken && plexToken !in it.uri })
                    assertEquals(4, plex.seen.count { it.uri == "/livetv/dvrs/10/reloadGuide" })
                }
            }
        }

    @Test fun `without SOFA_PLEX_URL there is no sync and the routes say so`() = testApplication {
        withRuntime(null) { runtime ->
            assertNull(runtime.plex)
            for (response in listOf(
                client.get("/api/v1/admin/plex") {
                    bearerAuth(admin)
                },
                client.post("/api/v1/admin/plex/sync") { bearerAuth(admin) },
            )) {
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals("PLEX_NOT_CONFIGURED", json.decodeFromString<ApiError>(response.bodyAsText()).code)
            }
            assertTrue(
                json.decodeFromString<PolicyFindings>(
                    client.get("/api/v1/admin/policy/findings") {
                        bearerAuth(admin)
                    }.bodyAsText(),
                ).findings.none { it.startsWith("plex:") },
            )
        }
    }
}
