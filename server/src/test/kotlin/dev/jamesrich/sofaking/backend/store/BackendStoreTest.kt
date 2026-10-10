package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.health.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.*

class BackendStoreTest {
    private val dirs = mutableListOf<Path>()
    private val t = Instant.parse("2026-10-10T00:00:00Z")
    private fun directory(): Path = Files.createTempDirectory("sofa-store-test-").also { dirs.add(it) }
    @AfterTest fun cleanup() { dirs.forEach { it.toFile().deleteRecursively() } }
    private fun observation(minute: Long, id: String = "failure-$minute") = Observation(id, t.plusSeconds(minute * 60), false, FailureClass.OFFERING)

    @Test fun `identity survives restart`() = runBlocking<Unit> {
        val dir = directory()
        val id = openStore(dir).use { it.serverId() }
        assertEquals(id, openStore(dir).use { it.serverId() })
    }
    @Test fun `store is ready only while open`() = runBlocking<Unit> {
        val store = openStore(directory())
        assertTrue(store.ready())
        store.close()
        assertFalse(store.ready())
        assertFailsWith<IllegalStateException> { store.serverId() }
    }
    @Test fun `registration and restart preserve quarantine`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { store ->
            store.registerOffering("provider:station")
            for (m in listOf(0L, 5L, 10L)) store.recordObservation("provider:station", observation(m), t.plusSeconds(m * 60))
        }
        openStore(dir).use { store ->
            store.registerOffering("provider:station")
            assertEquals(HealthStatus.QUARANTINED, store.health("provider:station")!!.status)
        }
    }
    @Test fun `same incident replayed after restart is a no op`() = runBlocking<Unit> {
        val dir = directory()
        val first = openStore(dir).use { store ->
            store.registerOffering("p:s")
            store.recordObservation("p:s", observation(0, "same"), t)
        }
        openStore(dir).use { store ->
            val result = store.recordObservation("p:s", observation(10, "same"), t.plusSeconds(600))
            assertEquals(first, result)
        }
    }
    @Test fun `concurrent reports cannot multiply one incident`() = runBlocking<Unit> {
        openStore(directory()).use { store ->
            store.registerOffering("p:s")
            coroutineScope { (1..10).map { async { store.recordObservation("p:s", observation(0, "same"), t) } }.awaitAll() }
            assertEquals(1, store.health("p:s")!!.failures.size)
        }
    }
    @Test fun `manual disable survives success registration and restart`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { store ->
            store.registerOffering("p:s")
            store.setManuallyDisabled("p:s", true)
            store.recordObservation("p:s", Observation("ok", t, true), t)
            store.registerOffering("p:s")
        }
        openStore(dir).use { assertTrue(it.health("p:s")!!.manuallyDisabled) }
    }
    @Test fun `probation and scheduled recovery survive restart`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { store ->
            store.registerOffering("p:s")
            for (m in listOf(0L, 5L, 10L)) store.recordObservation("p:s", observation(m), t.plusSeconds(m * 60))
            store.recordObservation("p:s", Observation("recovery", t.plusSeconds(1500), true), t.plusSeconds(1500))
        }
        openStore(dir).use { store ->
            val state = store.health("p:s")!!
            assertEquals(HealthStatus.PROBATION, state.status)
            assertEquals(t.plusSeconds(2100), state.nextCheckAt)
        }
    }
    @Test fun `unknown offering mutations reject rather than invent identity`() = runBlocking<Unit> {
        openStore(directory()).use { store ->
            assertNull(store.health("unknown"))
            assertFailsWith<IllegalArgumentException> { store.recordObservation("unknown", observation(0), t) }
            assertFailsWith<IllegalArgumentException> { store.setManuallyDisabled("unknown", true) }
            assertFailsWith<IllegalArgumentException> { store.registerOffering(" ") }
        }
    }
    @Test fun `unrelated registrations cannot reset disabled state`() = runBlocking<Unit> {
        openStore(directory()).use { store ->
            store.registerOffering("a")
            store.setManuallyDisabled("a", true)
            store.registerOffering("b")
            assertTrue(store.health("a")!!.manuallyDisabled)
            assertFalse(store.health("b")!!.manuallyDisabled)
        }
    }
    @Test fun `observation history prunes by retention while strikes and health survive`() = runBlocking<Unit> {
        openStore(directory()).use { store ->
            store.registerOffering("p:s")
            for (m in listOf(0L, 5L, 10L)) store.recordObservation("p:s", observation(m), t.plusSeconds(m * 60))
            assertEquals(2, store.pruneObservations(t.plusSeconds(10 * 60)))
            assertEquals(0, store.pruneObservations(t.plusSeconds(10 * 60)))
            val state = store.health("p:s")!!
            assertEquals(HealthStatus.QUARANTINED, state.status)
            assertEquals(3, state.failures.size)
            assertEquals(1, store.query { HealthObservations.selectAll().count() })
        }
    }
    @Test fun `probe attempts migrate out of metadata and health version tracks every write`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { it.registerOffering("p:s") }
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
            connection.createStatement().use {
                it.execute("DELETE FROM schema_version WHERE version >= 3")
                it.execute("DROP TABLE offering_protocol")
                it.execute("DROP TABLE probe_attempt")
                it.execute("DROP INDEX health_observation_observed_at")
                it.execute("DROP INDEX offering_health_updated_at")
                it.execute("INSERT INTO metadata VALUES ('probe:p:s', '2026-10-10T00:00:00Z')")
                it.execute("INSERT INTO metadata VALUES ('probe-unrelated', 'kept')")
            }
        }
        openStore(dir).use { store ->
            assertEquals(mapOf("p:s" to t), store.probeAttempts())
            assertEquals(0L, store.query { Metadata.selectAll().where { Metadata.key eq "probe:p:s" }.count() })
            assertEquals(1L, store.query { Metadata.selectAll().where { Metadata.key eq "probe-unrelated" }.count() })
            store.markProbeAttempt("p:s", t.plusSeconds(60))
            assertEquals(mapOf("p:s" to t.plusSeconds(60)), store.probeAttempts())
            val initial = store.healthVersion()
            assertEquals(initial, store.healthVersion())
            store.setManuallyDisabled("p:s", true)
            val disabled = store.healthVersion()
            assertNotEquals(initial, disabled)
            store.registerOffering("p:t")
            assertNotEquals(disabled, store.healthVersion())
        }
    }
    @Test fun `detected protocols replace earlier detections and survive restart`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { store ->
            assertEquals(emptyMap(), store.offeringProtocols())
            store.recordProtocol("p:s", "mpegts", t)
            store.recordProtocol("p:s", "hls", t.plusSeconds(60))
            store.recordProtocol("p:t", "mpegts", t)
            assertFailsWith<IllegalArgumentException> { store.recordProtocol("p:u", "rtsp", t) }
        }
        openStore(dir).use { assertEquals(mapOf("p:s" to "hls", "p:t" to "mpegts"), it.offeringProtocols()) }
    }
    @Test fun `newer schema refuses startup without losing data`() = runBlocking<Unit> {
        val dir = directory()
        val original = openStore(dir).use { it.serverId() }
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
            connection.createStatement().use { it.execute("INSERT INTO schema_version VALUES (999, '2026-10-10T00:00:00Z')") }
        }
        assertFailsWith<IllegalStateException> { openStore(dir) }
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT value FROM metadata WHERE key='server_id'").use { rs -> assertTrue(rs.next()); assertEquals(original, rs.getString(1)) }
            }
        }
    }
}
