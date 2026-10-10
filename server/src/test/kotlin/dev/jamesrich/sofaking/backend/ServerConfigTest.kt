package dev.jamesrich.sofaking.backend

import java.nio.file.Path
import kotlin.test.*

class ServerConfigTest {
    @Test fun `defaults keep server local with persistent data directory`() {
        val config = ServerConfig.fromEnvironment(emptyMap())
        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals(Path.of("data"), config.dataDirectory)
    }
    @Test fun `explicit environment chooses port host and data`() {
        val config = ServerConfig.fromEnvironment(mapOf("SOFA_HOST" to "192.168.1.3", "SOFA_PORT" to "9193", "SOFA_DATA_DIR" to "/tmp/sofa-config"))
        assertEquals("192.168.1.3", config.host)
        assertEquals(9193, config.port)
        assertEquals(Path.of("/tmp/sofa-config"), config.dataDirectory)
    }
    @Test fun `invalid port or empty values fail before binding`() {
        for (value in listOf("no", "0", "65536", "-1", "")) {
            assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_PORT" to value)) }
        }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_HOST" to " ")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_DATA_DIR" to "")) }
    }
}
