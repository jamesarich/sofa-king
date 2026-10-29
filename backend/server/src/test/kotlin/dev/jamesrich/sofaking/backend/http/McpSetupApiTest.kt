package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.SecretOrigin
import dev.jamesrich.sofaking.backend.SecretsFile
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.library.ServiceFailure
import dev.jamesrich.sofaking.backend.library.TmdbConfig
import dev.jamesrich.sofaking.backend.setup.SetupService
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SETUP_TOOLS = listOf("setup_status", "set_api_key", "write_starter_policy", "add_library")
private const val TMDB_SECRET = "tmdb-secret-value-0123456789abcdefghij"
private const val FANART_SECRET = "fanart-secret-value-0123456789abcdefg"
private const val NEW_SECRET = "brand-new-secret-value-0123456789abcd"

/** A setup service whose services answer with [check], else accept; the environment sets no variable. */
private fun setupChecking(check: (String) -> Unit = {}): (ServerConfig, () -> Unit) -> SetupService =
    { config, restart ->
        SetupService(
            config,
            restart,
            environment = emptyMap(),
            checkTmdb = { check(it.token) },
            checkFanart = { check(it) },
            checkListenBrainz = { check(it) },
        )
    }

class McpSetupApiTest {
    private val admin = MCP_ADMIN

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((name, value) in pairs) {
            if (value is Boolean) put(name, value) else put(name, value.toString())
        }
    }

    private suspend fun Mcp.resourceUris(bearer: String): List<String> =
        result(bearer, "resources/list")["resources"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content }

    private suspend fun Mcp.readResource(bearer: String, uri: String): String =
        result(bearer, "resources/read", buildJsonObject { put("uri", uri) })["contents"]!!.jsonArray.single()
            .jsonObject["text"]!!.jsonPrimitive.content

    private suspend fun Mcp.prompt(bearer: String, name: String, vararg pairs: Pair<String, String>): String = result(
        bearer,
        "prompts/get",
        buildJsonObject {
            put("name", name)
            put("arguments", buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } })
        },
    )["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content

    @Test fun `a read token is listed no setup tool and no policy resource and cannot use them`() = mcp(
        withPolicy = true,
        setup = setupChecking(),
    ) {
        val read = read()
        assertTrue(SETUP_TOOLS.none { it in tools(read) }, "a read token sees no setup tool: ${tools(read)}")
        assertTrue(tools(admin).containsAll(SETUP_TOOLS), "the admin token sees them: ${tools(admin)}")
        for (name in SETUP_TOOLS) {
            val message = Json.parseToJsonElement(
                post(
                    read,
                    "tools/call",
                    buildJsonObject {
                        put("name", name)
                        put("arguments", args("confirm" to true))
                    },
                ).bodyAsText(),
            ).jsonObject
            val refused = message["error"] != null ||
                message["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean == true
            assertTrue(refused, "a read token ran $name: $message")
        }
        val denied = Json.parseToJsonElement(
            post(read, "resources/read", buildJsonObject { put("uri", "sofa-king://policy") }).bodyAsText(),
        ).jsonObject
        assertTrue(denied["error"] != null, "a read token read the policy: $denied")
    }

    @Test fun `resources differ between the read and admin tokens and read back as documents`() = mcp(
        withPolicy = true,
    ) {
        val read = read()
        assertEquals(
            listOf("sofa-king://openapi.json", "sofa-king://support-snapshot"),
            resourceUris(read).sorted(),
        )
        assertEquals(
            listOf("sofa-king://openapi.json", "sofa-king://policy", "sofa-king://support-snapshot"),
            resourceUris(admin).sorted(),
        )

        for (bearer in listOf(read, admin)) {
            val openapi = Json.parseToJsonElement(readResource(bearer, "sofa-king://openapi.json")).jsonObject
            assertTrue("paths" in openapi, "the OpenAPI document has paths")
            val snapshot = readResource(bearer, "sofa-king://support-snapshot")
            val parsed = Json.parseToJsonElement(snapshot).jsonObject
            assertTrue("server" in parsed && "status" in parsed && "jobs" in parsed, snapshot)
            assertTrue(MCP_ADMIN !in snapshot && MCP_TOKEN !in snapshot, "the snapshot holds a token")
        }
        val policy = Json.parseToJsonElement(readResource(admin, "sofa-king://policy")).jsonObject
        assertTrue(policy["revision"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue("policy" in policy)

        val capabilities = result(
            admin,
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("capabilities", JsonObject(emptyMap()))
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "test")
                        put("version", "1")
                    },
                )
            },
        )["capabilities"]!!.jsonObject
        val resources = capabilities["resources"]!!.jsonObject
        assertEquals(false, resources["listChanged"]!!.jsonPrimitive.boolean)
        assertEquals(false, resources["subscribe"]!!.jsonPrimitive.boolean)
        assertTrue("prompts" in capabilities)
    }

    @Test fun `prompts list for both tokens and render with their argument`() = mcp {
        val read = read()
        val names = listOf("triage_server", "diagnose_channel", "review_policy_change")
        for (bearer in listOf(read, admin)) {
            val listed = result(bearer, "prompts/list")["prompts"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(names.sorted(), listed.sorted())
        }
        val triage = prompt(read, "triage_server")
        assertTrue(
            listOf("server_status", "needs_attention", "imports", "jobs", "recent_log").all { it in triage },
            triage,
        )
        assertTrue("admin token" in triage, triage)

        val diagnose = prompt(read, "diagnose_channel", "channel" to "Late Show 7")
        assertTrue("Late Show 7" in diagnose, diagnose)
        val steps = listOf("lineup", "source_health", "recent_log", "probe_source", "switch_session_source")
        assertTrue(steps.all { it in diagnose }, diagnose)

        val review = prompt(admin, "review_policy_change")
        assertTrue(listOf("preview_policy", "edit_station", "apply_policy").all { it in review }, review)

        val missing = Json.parseToJsonElement(
            post(
                read,
                "prompts/get",
                buildJsonObject {
                    put("name", "diagnose_channel")
                    put("arguments", JsonObject(emptyMap()))
                },
            ).bodyAsText(),
        ).jsonObject
        assertTrue(missing["error"] != null, "the channel argument is required: $missing")
    }

    @Test fun `setup_status holds no secret value and reports which keys are set`() = mcp(
        setup = setupChecking(),
        configure = {
            it.copy(
                tmdb = TmdbConfig(TMDB_SECRET),
                fanartKey = FANART_SECRET,
                secretOrigins = mapOf("SOFA_FANART_KEY" to SecretOrigin.ENVIRONMENT),
            )
        },
    ) {
        Files.writeString(
            dir.resolve("sources.json"),
            """{"providers":[
                {"id":"mine","playlistUrl":"http://user:hunter2@media.example.test:8080/list.m3u?key=zzsecret",
                 "headers":{"Authorization":"Bearer abc123secret"}},
                {"id":"named","playlistUrl":"http://${'$'}{PLAYLIST_HOST}/p.m3u?token=abc123secret"},
                {"id":"pluto","type":"native","service":"plutotv"}
            ],"guides":[{"id":"epg","url":"https://epg.example.test/guide.xml?k=zzsecret"}]}""",
        )
        val (failed, text) = call(admin, "setup_status")
        assertFalse(failed, text)
        for (secret in listOf(TMDB_SECRET, FANART_SECRET, "hunter2", "zzsecret", "abc123secret", "PLAYLIST_HOST")) {
            assertFalse(secret in text, "setup_status leaked $secret: $text")
        }
        assertFalse("://" in text || "Bearer" in text || MCP_ADMIN in text || MCP_TOKEN in text, text)

        val status = Json.parseToJsonElement(text).jsonObject
        val providers = status["providers"]!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals("media.example.test", providers.getValue("mine").jsonObject["host"]!!.jsonPrimitive.content)
        assertTrue("host" !in providers.getValue("named").jsonObject)
        assertEquals("native", providers.getValue("pluto").jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(900, providers.getValue("mine").jsonObject["refreshSeconds"]!!.jsonPrimitive.content.toInt())
        val keys = status["keys"]!!.jsonArray.associateBy { it.jsonObject["service"]!!.jsonPrimitive.content }
        fun flag(service: String, name: String) = keys.getValue(service).jsonObject[name]!!.jsonPrimitive.boolean
        assertTrue(flag("tmdb", "set") && !flag("tmdb", "fromEnvironment"))
        assertTrue(flag("fanart", "set") && flag("fanart", "fromEnvironment"))
        assertFalse(flag("listenbrainz", "set"))
        assertTrue(status["libraryFolders"]!!.jsonObject["roots"]!!.jsonArray.isNotEmpty())
    }

    @Test fun `set_api_key refuses a key the service rejects and writes nothing`() {
        val restarts = AtomicInteger()
        mcp(
            restart = { restarts.incrementAndGet() },
            setup = setupChecking { throw ServiceFailure("TMDB_AUTH") },
        ) {
            val (failed, text) = call(
                admin,
                "set_api_key",
                args("service" to "tmdb", "value" to NEW_SECRET, "confirm" to true),
            )
            assertTrue(failed && text.startsWith("TMDB_REJECTED: "), text)
            assertFalse(NEW_SECRET in text, text)
            assertFalse(Files.exists(dir.resolve(SecretsFile.NAME)), "nothing is written")
            assertEquals(0, restarts.get())
        }
    }

    @Test fun `set_api_key answers unreachable when the service cannot be asked`() = mcp(
        setup = setupChecking { throw ServiceFailure("LISTENBRAINZ_TIMEOUT") },
    ) {
        val (failed, text) = call(
            admin,
            "set_api_key",
            args("service" to "listenbrainz", "value" to NEW_SECRET, "confirm" to true),
        )
        assertTrue(failed && text.startsWith("LISTENBRAINZ_UNREACHABLE: "), text)
        assertFalse(Files.exists(dir.resolve(SecretsFile.NAME)))
    }

    @Test fun `set_api_key refuses a key the environment sets with the REST code`() {
        val restarts = AtomicInteger()
        mcp(
            restart = { restarts.incrementAndGet() },
            setup = setupChecking(),
            configure = { it.copy(secretOrigins = mapOf("SOFA_FANART_KEY" to SecretOrigin.ENVIRONMENT)) },
        ) {
            val (failed, text) = call(
                admin,
                "set_api_key",
                args("service" to "fanart", "value" to NEW_SECRET, "confirm" to true),
            )
            assertTrue(failed && text.startsWith("FANART_FROM_ENVIRONMENT: "), text)
            assertFalse(Files.exists(dir.resolve(SecretsFile.NAME)))
            assertEquals(0, restarts.get())
        }
    }

    @Test fun `set_api_key needs confirm and a known service`() {
        val restarts = AtomicInteger()
        mcp(restart = { restarts.incrementAndGet() }, setup = setupChecking()) {
            val (_, unconfirmed) = call(admin, "set_api_key", args("service" to "tmdb", "value" to NEW_SECRET))
            assertTrue(unconfirmed.startsWith("CONFIRMATION_REQUIRED: "), unconfirmed)
            val (_, unknown) = call(
                admin,
                "set_api_key",
                args("service" to "plex", "value" to NEW_SECRET, "confirm" to true),
            )
            assertTrue(unknown.startsWith("INVALID_REQUEST: "), unknown)
            val (_, short) = call(
                admin,
                "set_api_key",
                args("service" to "tmdb", "value" to "short", "confirm" to true),
            )
            assertTrue(short.startsWith("INVALID_REQUEST: "), short)
            assertFalse(Files.exists(dir.resolve(SecretsFile.NAME)))
            assertEquals(0, restarts.get())
        }
    }

    @Test fun `set_api_key saves the key without echoing it and restarts after the result`() {
        val events = CopyOnWriteArrayList<String>()
        val restarted = CountDownLatch(1)
        mcp(
            restart = {
                SetupService.afterDelay(SetupService.EXIT_DELAY_MILLIS) {
                    events += "restart"
                    restarted.countDown()
                }
            },
            setup = setupChecking(),
        ) {
            val (failed, text) = call(
                admin,
                "set_api_key",
                args("service" to "tmdb", "value" to NEW_SECRET, "confirm" to true),
            )
            events += "result"
            assertFalse(failed, text)
            assertFalse(NEW_SECRET in text, "the result echoed the key: $text")
            assertTrue(Json.parseToJsonElement(text).jsonObject["restarting"]!!.jsonPrimitive.boolean)
            assertEquals(NEW_SECRET, SecretsFile(dir.resolve(SecretsFile.NAME)).stored(SecretsFile.TMDB_TOKEN))
            assertEquals(listOf("result"), events.toList(), "the restart waits for the response")
        }
        assertTrue(restarted.await(SetupService.EXIT_DELAY_MILLIS * 4, TimeUnit.MILLISECONDS), "the restart ran")
        assertEquals(listOf("result", "restart"), events.toList())
    }

    @Test fun `write_starter_policy needs confirm then writes the policy and restarts once`() {
        val restarts = AtomicInteger()
        mcp(restart = { restarts.incrementAndGet() }, setup = setupChecking()) {
            val (_, unconfirmed) = call(admin, "write_starter_policy")
            assertTrue(unconfirmed.startsWith("CONFIRMATION_REQUIRED: "), unconfirmed)
            assertFalse(Files.exists(dir.resolve("policy.json")))
            assertEquals(0, restarts.get())

            val (failed, text) = call(admin, "write_starter_policy", args("confirm" to true))
            assertFalse(failed, text)
            assertTrue(Files.exists(dir.resolve("policy.json")), "the policy is written")
            assertEquals(1, restarts.get())
        }
    }

    @Test fun `write_starter_policy refuses when a policy is configured`() {
        val restarts = AtomicInteger()
        mcp(withPolicy = true, restart = { restarts.incrementAndGet() }, setup = setupChecking()) {
            val (failed, text) = call(admin, "write_starter_policy", args("confirm" to true))
            assertTrue(failed && text.startsWith("POLICY_EXISTS: "), text)
            assertEquals(0, restarts.get())
        }
    }

    @Test fun `add_library validates the kind and the folder the way the route does`() = mcp(
        setup = setupChecking(),
    ) {
        val tv = Files.createDirectories(dir.resolve("media/tv"))
        val (failed, text) = call(admin, "add_library", args("name" to "TV", "kind" to "shows", "path" to tv))
        assertFalse(failed, text)
        val created = Json.parseToJsonElement(text).jsonObject
        assertEquals("shows", created["kind"]!!.jsonPrimitive.content)
        assertEquals("TV", created["name"]!!.jsonPrimitive.content)

        val (_, listed) = call(admin, "libraries")
        assertTrue("TV" in listed, listed)
        val (_, again) = call(admin, "add_library", args("name" to "Again", "kind" to "shows", "path" to tv))
        assertTrue(again.startsWith("LIBRARY_EXISTS: "), again)
        val (_, kind) = call(admin, "add_library", args("name" to "X", "kind" to "cartoons", "path" to tv))
        assertTrue(kind.startsWith("INVALID_REQUEST: "), kind)
        val (outside, why) = call(admin, "add_library", args("name" to "Etc", "kind" to "videos", "path" to "/etc"))
        assertTrue(outside && why.isNotEmpty(), why)
    }
}
