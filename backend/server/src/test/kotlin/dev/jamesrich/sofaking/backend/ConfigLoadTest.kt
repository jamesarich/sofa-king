package dev.jamesrich.sofaking.backend

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLoadTest {
    private val dir: Path = Files.createTempDirectory("sofa-config-")
    private val legacy: Path = Files.createTempDirectory("sofa-legacy-")
    private val lines = mutableListOf<String>()

    @AfterTest fun cleanUp() { dir.toFile().deleteRecursively(); legacy.toFile().deleteRecursively() }

    private fun lan(vararg extra: Pair<String, String>) =
        mapOf("SOFA_HOST" to "0.0.0.0", "SOFA_HDHR_ENABLED" to "false", "SOFA_DATA_DIR" to dir.toString()) + extra

    private fun load(environment: Map<String, String>): ServerConfig = ServerConfig.load(environment, { lines += it }, legacyDataDirectory = legacy)

    private fun failure(environment: Map<String, String>): String = assertFailsWith<IllegalArgumentException> { load(environment) }.message.orEmpty()

    private val hex64 = Regex("[0-9a-f]{64}")

    @Test fun `a LAN bind without tokens generates both, stores them owner-only and logs where to read them, never a token`() {
        val first = load(lan())
        val api = assertNotNull(first.apiToken)
        val admin = assertNotNull(first.adminToken)
        assertTrue(api.matches(hex64) && admin.matches(hex64) && api != admin)
        assertEquals(SecretOrigin.GENERATED, first.secretOrigins["SOFA_API_TOKEN"])
        assertEquals(SecretOrigin.GENERATED, first.secretOrigins["SOFA_ADMIN_TOKEN"])
        val file = dir.resolve("secrets.json")
        assertEquals(file, first.secretsFile)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(listOf("secrets.json"), Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() }, "no temporary file is left")
        assertTrue(Files.readString(file).contains(api) && Files.readString(file).contains(admin))
        assertTrue(lines.none { admin in it || api in it }, "no token is logged: $lines")
        val generated = lines.single { it.startsWith("secrets: generated SOFA_ADMIN_TOKEN") }
        assertTrue(generated.contains("stored it in $file"), "the line says where it is stored: $generated")
        assertTrue(generated.contains("docker compose exec sofa-king-backend cat /config/secrets.json"), "and how to read it: $generated")
        assertTrue(generated.contains("Settings"), "and that the portal reveals the API token: $generated")

        lines.clear()
        val second = load(lan())
        assertEquals(api, second.apiToken)
        assertEquals(admin, second.adminToken)
        assertTrue(lines.none { admin in it || api in it }, "a later start logs no token: $lines")
        assertTrue(lines.any { it.contains("SOFA_ADMIN_TOKEN from $file") })
    }

    @Test fun `an environment token wins over the stored one and only the missing token is generated`() {
        val stored = load(lan())
        lines.clear()
        val apiToken = "a".repeat(40)
        val withApi = load(lan("SOFA_API_TOKEN" to apiToken))
        assertEquals(apiToken, withApi.apiToken)
        assertEquals(SecretOrigin.ENVIRONMENT, withApi.secretOrigins["SOFA_API_TOKEN"])
        assertEquals(stored.adminToken, withApi.adminToken)
        assertEquals(SecretOrigin.GENERATED, withApi.secretOrigins["SOFA_ADMIN_TOKEN"])

        val adminToken = "b".repeat(40)
        val all = load(lan("SOFA_API_TOKEN" to apiToken, "SOFA_ADMIN_TOKEN" to adminToken, "SOFA_OUTPUT_TOKEN" to "c".repeat(40)))
        assertEquals(adminToken, all.adminToken)
        assertNull(all.secretsFile, "nothing is generated or read when the environment sets every token")

        Files.delete(dir.resolve("secrets.json"))
        val adminOnly = load(lan("SOFA_ADMIN_TOKEN" to adminToken))
        assertEquals(adminToken, adminOnly.adminToken)
        assertTrue(assertNotNull(adminOnly.apiToken).matches(hex64))
        assertFalse(Files.readString(dir.resolve("secrets.json")).contains("adminToken"), "an environment token is never stored")
        assertTrue(lines.none { adminToken in it || apiToken in it })
    }

    @Test fun `a LAN bind generates the output token too, valid for player URLs, and an environment one wins`() {
        val first = load(lan())
        val output = assertNotNull(first.outputs.token)
        assertTrue(output.matches(hex64) && output != first.apiToken && output != first.adminToken)
        assertEquals(SecretOrigin.GENERATED, first.secretOrigins["SOFA_OUTPUT_TOKEN"])
        assertTrue(Files.readString(dir.resolve("secrets.json")).contains("\"outputToken\""))
        assertTrue(lines.any { it.startsWith("secrets: generated SOFA_OUTPUT_TOKEN") } && lines.none { output in it }, "no token is logged: $lines")
        assertEquals(output, load(lan()).outputs.token, "a later start reuses it")
        assertEquals(output, ServerConfig.fromEnvironment(lan("SOFA_OUTPUT_TOKEN" to output, "SOFA_API_TOKEN" to "a".repeat(40))).outputs.token, "the generated token passes the variable's own check")

        val chosen = "c".repeat(40)
        val set = load(lan("SOFA_OUTPUT_TOKEN" to chosen))
        assertEquals(chosen, set.outputs.token)
        assertEquals(SecretOrigin.ENVIRONMENT, set.secretOrigins["SOFA_OUTPUT_TOKEN"])
        assertNull(load(mapOf("SOFA_DATA_DIR" to dir.resolve("loopback").toString())).outputs.token, "a loopback bind generates none")
    }

    @Test fun `environment tokens keep their validation`() {
        assertEquals("SOFA_API_TOKEN must contain at least 32 characters", failure(lan("SOFA_API_TOKEN" to "short")))
        assertEquals("SOFA_ADMIN_TOKEN must contain at least 32 characters", failure(lan("SOFA_ADMIN_TOKEN" to "short")))
        assertFalse(Files.exists(dir.resolve("secrets.json")))
    }

    @Test fun `a loopback bind generates nothing and leaves unset tokens unset`() {
        val config = load(mapOf("SOFA_DATA_DIR" to dir.toString()))
        assertNull(config.apiToken)
        assertNull(config.adminToken)
        assertFalse(Files.exists(dir.resolve("secrets.json")))
    }

    @Test fun `fromEnvironment alone still refuses a LAN bind without a token`() {
        assertEquals("Non-loopback binding requires SOFA_API_TOKEN", assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(lan()) }.message)
    }

    @Test fun `a damaged secrets file stops the start without printing its contents`() {
        Files.writeString(dir.resolve("secrets.json"), """{"apiToken":"tooShortSecret"}""")
        val message = failure(lan())
        assertTrue(message.contains("invalid apiToken"), message)
        assertFalse(message.contains("tooShortSecret"))
        Files.writeString(dir.resolve("secrets.json"), "not json")
        assertTrue(failure(lan()).contains("not a JSON object"))
    }

    @Test fun `FILE variables supply each secret without the trailing line break`() {
        val values = mapOf(
            "SOFA_API_TOKEN" to "api-".repeat(10), "SOFA_ADMIN_TOKEN" to "adm-".repeat(10),
            "SOFA_OUTPUT_TOKEN" to "out_".repeat(10), "SOFA_PLEX_TOKEN" to "plexToken99",
        )
        val environment = lan("SOFA_PLEX_URL" to "http://plex.lan:32400", "SOFA_HDHR_ENABLED" to "true", "SOFA_HDHR_ALLOW" to "192.168.1.10").toMutableMap()
        for ((name, value) in values) {
            val file = Files.writeString(dir.resolve("$name.txt"), value + "\n")
            environment["${name}_FILE"] = file.toString()
        }
        val config = load(environment)
        assertEquals(values["SOFA_API_TOKEN"], config.apiToken)
        assertEquals(values["SOFA_ADMIN_TOKEN"], config.adminToken)
        assertEquals(values["SOFA_OUTPUT_TOKEN"], config.outputs.token)
        assertEquals(values["SOFA_PLEX_TOKEN"], config.plex?.token)
        for (name in values.keys) assertEquals(SecretOrigin.FILE, config.secretOrigins[name], name)
        assertNull(config.secretsFile)
        assertTrue(lines.none { line -> values.values.any { it in line } })
    }

    @Test fun `setting a secret and its FILE variable is an error naming both`() {
        for (name in FILE_SECRETS) {
            val file = Files.writeString(dir.resolve("value"), "x".repeat(40))
            val message = failure(lan(name to "y".repeat(40), "${name}_FILE" to file.toString()))
            assertEquals("Set $name or ${name}_FILE, not both", message)
        }
    }

    @Test fun `an unreadable FILE variable is an error and the value read is validated`() {
        val missing = dir.resolve("absent")
        val message = failure(lan("SOFA_API_TOKEN_FILE" to missing.toString()))
        assertTrue(message.startsWith("SOFA_API_TOKEN_FILE names $missing, which cannot be read"), message)
        val unreadable = Files.writeString(dir.resolve("locked"), "z".repeat(40))
        // The reader stands in for a file the server's user may not open; tests can run as root, which opens anything.
        val refused = assertFailsWith<IllegalArgumentException> {
            ServerConfig.load(lan("SOFA_ADMIN_TOKEN_FILE" to unreadable.toString()), { lines += it }, readFile = { throw java.nio.file.AccessDeniedException(it.toString()) }, legacyDataDirectory = legacy)
        }.message.orEmpty()
        assertTrue(refused.startsWith("SOFA_ADMIN_TOKEN_FILE names $unreadable, which cannot be read"), refused)
        assertFalse(refused.contains("z".repeat(40)))
        val short = Files.writeString(dir.resolve("short"), "tiny\n")
        assertEquals("SOFA_API_TOKEN must contain at least 32 characters", failure(lan("SOFA_API_TOKEN_FILE" to short.toString())))
        assertEquals("SOFA_OUTPUT_TOKEN must contain at least 32 letters, digits, hyphens or underscores", failure(lan("SOFA_OUTPUT_TOKEN_FILE" to short.toString())))
    }

    @Test fun `sources and policy files in the data directory are used when their variables are unset`() {
        val sources = Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val policy = Files.writeString(dir.resolve("policy.json"), "{}")
        val found = load(mapOf("SOFA_DATA_DIR" to dir.toString()))
        assertEquals(sources, found.sourcesFile)
        assertEquals(policy, found.policyFile)
        assertEquals(setOf("SOFA_SOURCES", "SOFA_POLICY"), found.foundInDataDirectory)
        assertTrue(lines.any { it == "config: sources file=$sources from=SOFA_DATA_DIR, SOFA_SOURCES is unset" }, lines.toString())
        assertTrue(lines.any { it == "config: lineup policy file=$policy from=SOFA_DATA_DIR, SOFA_POLICY is unset" }, lines.toString())

        lines.clear()
        val elsewhere = Files.createDirectories(dir.resolve("elsewhere"))
        val set = load(mapOf("SOFA_DATA_DIR" to dir.toString(), "SOFA_SOURCES" to elsewhere.resolve("s.json").toString(), "SOFA_POLICY" to elsewhere.resolve("p.json").toString()))
        assertEquals(elsewhere.resolve("s.json"), set.sourcesFile)
        assertEquals(elsewhere.resolve("p.json"), set.policyFile)
        assertTrue(set.foundInDataDirectory.isEmpty())
        assertTrue(lines.any { it == "config: sources file=${elsewhere.resolve("s.json")} from=SOFA_SOURCES" }, lines.toString())
    }

    @Test fun `without files or variables there is no sources or policy file and the log says how to add one`() {
        val config = load(mapOf("SOFA_DATA_DIR" to dir.toString()))
        assertNull(config.sourcesFile)
        assertNull(config.policyFile)
        assertTrue(lines.contains("config: no sources file; set SOFA_SOURCES or add sources.json to $dir"), lines.toString())
        assertTrue(lines.contains("config: no lineup policy file; set SOFA_POLICY or add policy.json to $dir"), lines.toString())
    }

    @Test fun `a database left in the legacy data directory stops a start on an empty data directory`() {
        Files.writeString(legacy.resolve("backend.sqlite"), "")
        val message = failure(lan())
        assertTrue(message.contains("${legacy.resolve("backend.sqlite")} exists") && message.contains("SOFA_DATA_DIR=$legacy"), message)
        assertFalse(Files.exists(dir.resolve("secrets.json")), "nothing is generated before the check")
        assertNotNull(load(lan("SOFA_DATA_DIR" to legacy.toString())).adminToken, "pointing SOFA_DATA_DIR at it starts")
        Files.writeString(dir.resolve("backend.sqlite"), "")
        assertNotNull(load(lan()).adminToken, "a data directory with its own database starts")
    }
}
