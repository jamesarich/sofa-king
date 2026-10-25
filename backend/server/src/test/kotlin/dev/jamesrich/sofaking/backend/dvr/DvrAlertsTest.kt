package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.api.ServerEvent
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DvrAlertsTest {
    private val start = Instant.parse("2026-10-17T20:00:00Z")

    /** One airing per channel at [start], since the store keeps one recording per channel and start. */
    private fun recording(id: String, created: Long) = StoredRecording(
        id, null, "channel-$id", "Channel $id", start, start.plusSeconds(1800),
        Duration.ofSeconds(60), Duration.ofSeconds(120), "News $id",
        state = Wire.RecordingState.SCHEDULED, createdAt = start.minusSeconds(10_000 - created),
        updatedAt = start.minusSeconds(10_000 - created),
    )

    @Test fun `a new conflict is published once, and those a restart finds are not`() = alerts { store, alerts, seen ->
        store.addRecording(recording("a", 1))
        store.addRecording(recording("b", 2))
        alerts.checkConflicts()
        assertEquals(emptyList(), seen(), "the first check only learns what conflicts already")
        store.addRecording(recording("c", 3))
        alerts.checkConflicts()
        alerts.checkConflicts()
        val conflicts = seen().filter { it.type == EventType.RECORDING_CONFLICT }
        assertEquals(listOf("c"), conflicts.map { it.data["id"]!!.jsonPrimitive.content })
    }

    @Test fun `disk low is published as the free space drops under the floor, once until it recovers`() =
        alerts { _, alerts, seen ->
            val gib = 1024L * 1024 * 1024
            alerts.space(5 * gib, 10 * gib)
            alerts.space(4 * gib, 10 * gib)
            alerts.space(null, 10 * gib)
            alerts.space(20 * gib, 10 * gib)
            alerts.space(3 * gib / 2, 10 * gib)
            val low = seen().filter { it.type == EventType.DISK_LOW }
            assertEquals(listOf("5.0", "1.5"), low.map { it.data["freeGib"]!!.jsonPrimitive.content })
            assertEquals("10", low.first().data["floorGib"]!!.jsonPrimitive.content)
        }

    private fun alerts(
        block: suspend (
            BackendStore,
            DvrAlerts,
            suspend () -> List<ServerEvent>,
        ) -> Unit,
    ) = runBlocking {
        val dir = Files.createTempDirectory("sofa-dvr-alerts-")
        val events = ServerEvents(MutableClock())
        val received = mutableListOf<ServerEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { events.events.toList(received) }
        try {
            openStore(dir).use { store ->
                block(store, DvrAlerts(store, events) { 1 }) {
                    yield()
                    received.toList()
                }
            }
        } finally {
            collector.cancel()
            dir.toFile().deleteRecursively()
        }
    }
}
