package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.SecretOrigin
import dev.jamesrich.sofaking.backend.ServerConfig
import java.nio.file.Path
import kotlin.test.*

class EffectiveSettingsTest {
    private val api = "a".repeat(32)
    private val admin = "b".repeat(32)

    private fun setting(groups: List<SettingGroup>, name: String): Setting = groups.flatMap { it.settings }.single { it.name == name }

    @Test fun `tokens are reported by origin and never by value`() {
        val config = ServerConfig.fromEnvironment(mapOf("SOFA_API_TOKEN" to api, "SOFA_ADMIN_TOKEN" to admin)).copy(
            secretOrigins = mapOf("SOFA_API_TOKEN" to SecretOrigin.ENVIRONMENT, "SOFA_ADMIN_TOKEN" to SecretOrigin.GENERATED),
            secretsFile = Path.of("/config/secrets.json"),
        )
        val groups = effectiveSettings(config)
        val apiToken = setting(groups, "SOFA_API_TOKEN")
        assertEquals("set", apiToken.value)
        assertFalse(apiToken.isDefault)
        assertEquals("Set in the environment. Opens /api/v1; the Sofa King app needs it", apiToken.note)
        val adminToken = setting(groups, "SOFA_ADMIN_TOKEN")
        assertEquals("generated", adminToken.value)
        assertTrue(adminToken.note!!.startsWith("Generated and stored in /config/secrets.json. "), adminToken.note)
        val plexToken = setting(groups, "SOFA_PLEX_TOKEN")
        assertEquals("not set", plexToken.value)
        assertTrue(plexToken.isDefault)
        val text = groups.flatMap { it.settings }.joinToString { "${it.name}=${it.value} ${it.note}" }
        assertFalse(text.contains(api) || text.contains(admin), "no token value reaches the page: $text")
    }

    @Test fun `a setting is marked default only when it equals the default`() {
        val defaults = effectiveSettings(ServerConfig.fromEnvironment(emptyMap()))
        for (name in listOf("SOFA_HOST", "SOFA_PORT", "SOFA_DATA_DIR", "SOFA_FFMPEG")) assertTrue(setting(defaults, name).isDefault, name)
        assertEquals("127.0.0.1", setting(defaults, "SOFA_HOST").value)
        assertEquals("not set", setting(defaults, "SOFA_SOURCES").value)
        assertTrue(setting(defaults, "SOFA_SOURCES").isDefault)
        assertNull(setting(defaults, "SOFA_SOURCES").note)

        val custom = effectiveSettings(
            ServerConfig.fromEnvironment(mapOf("SOFA_PORT" to "9193", "SOFA_MAX_SESSIONS" to "2", "SOFA_DATA_DIR" to "/srv/sofa"))
                .copy(foundInDataDirectory = setOf("SOFA_SOURCES"), sourcesFile = Path.of("/srv/sofa/sources.json")),
        )
        assertEquals("9193", setting(custom, "SOFA_PORT").value)
        assertFalse(setting(custom, "SOFA_PORT").isDefault)
        assertTrue(custom.flatMap { it.settings }.none { it.name == "SOFA_MAX_SESSIONS" }, "a runtime setting is edited on its own panel, not listed here")
        assertEquals("/srv/sofa/sources.json", setting(custom, "SOFA_SOURCES").value)
        assertEquals("Found in SOFA_DATA_DIR; SOFA_SOURCES is unset", setting(custom, "SOFA_SOURCES").note)
    }
}
