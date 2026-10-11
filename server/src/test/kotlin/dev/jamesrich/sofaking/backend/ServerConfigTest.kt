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
        val config = ServerConfig.fromEnvironment(mapOf("SOFA_HOST" to "192.168.1.3", "SOFA_API_TOKEN" to "x".repeat(32), "SOFA_PORT" to "9193", "SOFA_DATA_DIR" to "/tmp/sofa-config"))
        assertEquals("192.168.1.3", config.host)
        assertEquals(9193, config.port)
        assertEquals(Path.of("/tmp/sofa-config"), config.dataDirectory)
    }
    @Test fun `LAN binding requires token and capacity is bounded`() {
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_HOST" to "0.0.0.0")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_API_TOKEN" to "short")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_MAX_SESSIONS" to "100")) }
    }
    @Test fun `probe parallelism and restream origins are parsed and bounded`() {
        val config = ServerConfig.fromEnvironment(mapOf("SOFA_PROBE_PARALLELISM" to "3", "SOFA_RESTREAM_ORIGINS" to " 192.168.1.3:3000, Proxy.LAN ,"))
        assertEquals(3, config.probeParallelism)
        assertEquals(setOf("192.168.1.3:3000", "proxy.lan"), config.restreamOrigins)
        assertEquals(6, ServerConfig.fromEnvironment(emptyMap()).probeParallelism)
        assertTrue(ServerConfig.fromEnvironment(emptyMap()).restreamOrigins.isEmpty())
        for (value in listOf("0", "33", "many")) {
            assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_PROBE_PARALLELISM" to value)) }
        }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_RESTREAM_ORIGINS" to "http://192.168.1.3:3000")) }
        assertEquals(7, ServerConfig.fromEnvironment(emptyMap()).observationRetentionDays)
        assertEquals(30, ServerConfig.fromEnvironment(mapOf("SOFA_OBSERVATION_RETENTION_DAYS" to "30")).observationRetentionDays)
        for (value in listOf("0", "3651", "week")) {
            assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_OBSERVATION_RETENTION_DAYS" to value)) }
        }
        assertFalse(ServerConfig.fromEnvironment(emptyMap()).allowLoopbackUpstreams)
        assertTrue(ServerConfig.fromEnvironment(mapOf("SOFA_ALLOW_LOOPBACK_UPSTREAMS" to "TRUE")).allowLoopbackUpstreams)
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_ALLOW_LOOPBACK_UPSTREAMS" to "yes")) }
        assertNull(ServerConfig.fromEnvironment(emptyMap()).adminToken)
        assertEquals("a".repeat(32), ServerConfig.fromEnvironment(mapOf("SOFA_ADMIN_TOKEN" to "a".repeat(32))).adminToken)
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_ADMIN_TOKEN" to "short")) }
    }
    @Test fun `output token and XMLTV depth are parsed and bounded`() {
        assertEquals(OutputConfig(), ServerConfig.fromEnvironment(emptyMap()).outputs)
        val config = ServerConfig.fromEnvironment(mapOf("SOFA_OUTPUT_TOKEN" to "p-_".repeat(11), "SOFA_XMLTV_DAYS" to "7"))
        assertEquals(OutputConfig("p-_".repeat(11), 7), config.outputs)
        for (value in listOf("short", "a b".repeat(16), "token/".repeat(8))) {
            assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_OUTPUT_TOKEN" to value)) }
        }
        for (value in listOf("0", "15", "week")) {
            assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_XMLTV_DAYS" to value)) }
        }
    }
    @Test fun `invalid port or empty values fail before binding`() {
        for (value in listOf("no", "0", "65536", "-1", "")) {
            assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_PORT" to value)) }
        }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_HOST" to " ")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(mapOf("SOFA_DATA_DIR" to "")) }
    }
}
