package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.PlexDeviceStatus
import dev.jamesrich.sofaking.backend.api.PlexStatus
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
    @Test fun `health writes are visible at once and persist across reopen`() = runBlocking<Unit> {
        val dir = directory()
        val before = openStore(dir).use { store ->
            store.registerOffering("p:s")
            assertEquals(HealthState(), store.health("p:s"))
            val next = store.recordObservation("p:s", observation(0, "a"), t)
            assertEquals(next, store.health("p:s"))
            assertEquals(mapOf("p:s" to next), store.allHealth())
            store.setManuallyDisabled("p:s", true)
            assertTrue(store.health("p:s")!!.manuallyDisabled)
            assertTrue(store.allHealth().getValue("p:s").manuallyDisabled)
            store.allHealth()
        }
        openStore(dir).use { store -> assertEquals(before, store.allHealth()) }
    }
    @Test fun `snapshot import publishes new offerings and keeps existing health`() = runBlocking<Unit> {
        openStore(directory()).use { store ->
            store.registerOffering("p:old")
            store.setManuallyDisabled("p:old", true)
            val offerings = listOf(
                dev.jamesrich.sofaking.backend.catalog.Offering("p:old", "c", "p", "http://old", "hls"),
                dev.jamesrich.sofaking.backend.catalog.Offering("p:new", "c", "p", "http://new", "hls"),
            )
            store.saveSnapshot(dev.jamesrich.sofaking.backend.catalog.FeedSnapshot("p", emptyList(), offerings, t.toString()))
            assertEquals(HealthState(), store.health("p:new"))
            assertTrue(store.health("p:old")!!.manuallyDisabled)
            assertEquals(setOf("p:old", "p:new"), store.allHealth().keys)
        }
    }
    @Test fun `a stored snapshot with fields the model does not declare still loads`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { it.serverId() }
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("""INSERT INTO metadata(key, value) VALUES ('feed:p', '{"providerId":"p","channels":[],"offerings":[],"programmes":[{"id":"x"}],"importedAt":"2026-10-10T00:00:00Z"}')""")
            }
        }
        openStore(dir).use { store -> assertEquals(listOf(dev.jamesrich.sofaking.backend.catalog.FeedSnapshot("p", emptyList(), emptyList(), t.toString())), store.snapshots()) }
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
    @Test fun `a fresh store has no Plex status`() = runBlocking<Unit> {
        openStore(directory()).use { store -> assertNull(store.plexStatus()) }
    }
    @Test fun `the Plex status persists across a reopen and a new one replaces it`() = runBlocking<Unit> {
        val dir = directory()
        val first = PlexStatus(
            30, t.toString(), "startup", null, listOf("10"), listOf(PlexDeviceStatus("ABCDEF01", 0, "20", "10", 42)), listOf("12345678"),
            listOf("plex: device 12345678 (device 1) is not added to Plex"),
        )
        openStore(dir).use { it.savePlexStatus(first) }
        openStore(dir).use { store ->
            assertEquals(first, store.plexStatus())
            store.savePlexStatus(PlexStatus(30, t.plusSeconds(60).toString(), "interval", "GET /livetv/dvrs answered 401"))
        }
        openStore(dir).use { store ->
            val stored = store.plexStatus()!!
            assertEquals("interval", stored.lastCause)
            assertEquals("GET /livetv/dvrs answered 401", stored.lastError)
            assertEquals(emptyList(), stored.devices)
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
                // Versions 3 and 4 are replayed while 10 stays applied: the gap-tolerant migrator fills only what is missing.
                it.execute("DELETE FROM schema_version WHERE version IN (3, 4)")
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

    @Test fun `stored times come from the injected clock`() = runBlocking<Unit> {
        val dir = directory()
        val then = Instant.parse("2000-01-01T00:00:00Z")
        openStore(dir, dev.jamesrich.sofaking.backend.catalog.MutableClock(then)).use { store ->
            store.registerOffering("p:old")
            assertEquals(1, store.pruneOfferings(emptySet(), then.plusSeconds(1)), "the row is dated by the clock, not the wall")
        }
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT DISTINCT applied_at FROM schema_version").use { rs -> assertTrue(rs.next()); assertEquals(then.toString(), rs.getString(1)); assertFalse(rs.next()) }
            }
        }
    }

    @Test fun `a guide import refreshes the planner statistics`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { store ->
            val channels = List(50) { dev.jamesrich.sofaking.backend.catalog.GuideChannel("c$it", "Channel $it") }
            val programs = List(2000) { i -> dev.jamesrich.sofaking.backend.catalog.GuideProgram("c${i % 50}", t.plusSeconds(i * 600L), t.plusSeconds(i * 600L + 600), "Show $i") }
            store.saveGuide("g", t, channels, programs)
            val analyzed = store.query { exec("SELECT DISTINCT tbl FROM sqlite_stat1") { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }.orEmpty()
            assertTrue("guide_program" in analyzed, "$analyzed")
        }
    }

    @Test fun `a read answers while a long write holds the writer`() = runBlocking<Unit> {
        openStore(directory()).use { store ->
            store.saveLineup(emptyList(), t)
            val readDone = java.util.concurrent.CountDownLatch(1)
            val write = async(Dispatchers.Default) { store.query { readDone.await(10, java.util.concurrent.TimeUnit.SECONDS) } }
            withTimeout(5_000) { assertEquals(emptyList(), store.lineup()) }
            readDone.countDown()
            assertTrue(write.await(), "the write was still open when the read answered")
        }
    }

    @Test fun `provider state reads from memory, follows each write and survives a reopen`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { store ->
            store.saveProviderState("p", "device", "one", null, t)
            store.saveProviderState("p", "token", "secret", t.plusSeconds(60), t)
            store.saveProviderState("p", "device", "two", null, t.plusSeconds(1))
            store.deleteProviderState("p", "token")
            assertEquals(ProviderStateValue("two", null, t.plusSeconds(1)), store.providerState("p", "device"))
            assertNull(store.providerState("p", "token"))
        }
        openStore(dir).use { store ->
            assertEquals(ProviderStateValue("two", null, t.plusSeconds(1)), store.providerState("p", "device"))
            assertNull(store.providerState("p", "token"))
        }
    }

    @Test fun `a transaction that holds its connection past half a second is logged by its source file`() = runBlocking<Unit> {
        val logger = org.slf4j.LoggerFactory.getLogger(BackendStore::class.java) as ch.qos.logback.classic.Logger
        val seen = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(seen)
        try {
            openStore(directory()).use { store ->
                store.query { Thread.sleep(600) }
                store.query { }
            }
            val slow = seen.list.filter { it.formattedMessage.startsWith("slow database transaction") }
            assertEquals(1, slow.size, seen.list.map { it.formattedMessage }.toString())
            assertTrue(slow.single().formattedMessage.contains("source=BackendStoreTest"), slow.single().formattedMessage)
        } finally { logger.detachAppender(seen) }
    }
}
