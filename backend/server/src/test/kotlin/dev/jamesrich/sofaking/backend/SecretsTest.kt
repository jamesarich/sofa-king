package dev.jamesrich.sofaking.backend

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadSecretFilesTest {
    private val files = mapOf(
        Path.of("/run/secrets/api") to "t".repeat(32) + "\n",
        Path.of("/run/secrets/plex") to "plex-token\r\n",
    )
    private fun read(path: Path): String = files[path] ?: throw IOException("missing")

    @Test fun `a FILE variable replaces its NAME with the file's contents minus trailing line breaks`() {
        val (resolved, origins) = readSecretFiles(
            mapOf(
                "SOFA_API_TOKEN_FILE" to "/run/secrets/api",
                "SOFA_PLEX_TOKEN_FILE" to "/run/secrets/plex",
                "SOFA_ADMIN_TOKEN" to "a".repeat(32),
                "SOFA_HOST" to "0.0.0.0",
            ),
            ::read,
        )
        assertEquals("t".repeat(32), resolved["SOFA_API_TOKEN"])
        assertEquals("plex-token", resolved["SOFA_PLEX_TOKEN"])
        assertNull(resolved["SOFA_API_TOKEN_FILE"], "the FILE variable is consumed")
        assertEquals("0.0.0.0", resolved["SOFA_HOST"], "other variables pass through")
        assertEquals(
            mapOf(
                "SOFA_API_TOKEN" to SecretOrigin.FILE,
                "SOFA_PLEX_TOKEN" to SecretOrigin.FILE,
                "SOFA_ADMIN_TOKEN" to SecretOrigin.ENVIRONMENT,
            ),
            origins,
        )
        assertEquals(emptyMap(), readSecretFiles(emptyMap(), ::read).second, "an unset secret has no origin")
    }

    @Test fun `a secret set both ways, a blank path or an unreadable file is refused by name without the contents`() {
        assertEquals(
            "Set SOFA_API_TOKEN or SOFA_API_TOKEN_FILE, not both",
            assertFailsWith<IllegalArgumentException> {
                readSecretFiles(
                    mapOf("SOFA_API_TOKEN" to "x".repeat(32), "SOFA_API_TOKEN_FILE" to "/run/secrets/api"),
                    ::read,
                )
            }.message,
        )
        assertEquals(
            "SOFA_API_TOKEN_FILE must not be blank",
            assertFailsWith<IllegalArgumentException> {
                readSecretFiles(mapOf("SOFA_API_TOKEN_FILE" to " "), ::read)
            }.message,
        )
        val message = assertFailsWith<IllegalArgumentException> {
            readSecretFiles(mapOf("SOFA_OUTPUT_TOKEN_FILE" to "/run/secrets/none"), ::read)
        }.message.orEmpty()
        assertEquals("SOFA_OUTPUT_TOKEN_FILE names /run/secrets/none, which cannot be read (IOException)", message)
    }
}

class SecretsFileTest {
    private val dir: Path = Files.createTempDirectory("sofa-secrets-")

    @AfterTest fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test fun `missing names are generated once, stored and read back unchanged`() {
        val file = SecretsFile(dir.resolve(SecretsFile.NAME))
        val (first, generated) = file.resolve(listOf("apiToken", "adminToken"))
        assertEquals(setOf("apiToken", "adminToken"), generated)
        assertTrue(first.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        assertNotEquals(first["apiToken"], first["adminToken"])
        val (second, generatedAgain) = SecretsFile(file.path).resolve(listOf("adminToken", "apiToken"))
        assertEquals(first, second)
        assertEquals(emptySet(), generatedAgain)
        val (third, onlyNew) = SecretsFile(file.path).resolve(listOf("apiToken", "outputToken"))
        assertEquals(first["apiToken"], third["apiToken"])
        assertEquals(setOf("outputToken"), onlyNew)
        assertEquals(
            listOf(SecretsFile.NAME),
            Files.list(dir).use { files ->
                files.map { it.fileName.toString() }.toList()
            },
            "no temporary file is left",
        )
        assertTrue(Files.readString(file.path).endsWith("}\n"))
    }

    @Test fun `a damaged file or entry is refused with advice and never partly rewritten`() {
        val path = dir.resolve(SecretsFile.NAME)
        Files.writeString(path, "[]")
        assertEquals(
            "$path is not a JSON object; delete it to generate new tokens",
            assertFailsWith<IllegalArgumentException> {
                SecretsFile(path).resolve(listOf("apiToken"))
            }.message,
        )
        Files.writeString(path, """{"apiToken":"short"}""")
        assertEquals(
            "$path holds an invalid apiToken; delete that entry to generate a new one",
            assertFailsWith<IllegalArgumentException> {
                SecretsFile(path).resolve(listOf("apiToken"))
            }.message,
        )
        Files.writeString(path, """{"apiToken":"${"a".repeat(16)} ${"b".repeat(16)}"}""")
        assertFailsWith<IllegalArgumentException> { SecretsFile(path).resolve(listOf("adminToken")) }
        assertEquals(
            """{"apiToken":"${"a".repeat(16)} ${"b".repeat(16)}"}""",
            Files.readString(path),
            "the file is untouched",
        )
    }
}
