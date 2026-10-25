package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.NotificationDelivery
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationStorageTest {
    @Test fun `each target keeps its newest deliveries, and a removed target none`() = runBlocking {
        val dir = Files.createTempDirectory("sofa-notify-store-")
        try {
            openStore(dir).use { store ->
                for (id in listOf("a", "b")) {
                    val events = listOf("disk.low")
                    store.saveNotificationTarget(
                        StoredNotificationTarget(id, id, "webhook", "http://192.0.2.10/", null, events, true, "t", "t"),
                    )
                }
                repeat(NOTIFICATION_DELIVERIES_KEPT + 5) { n ->
                    store.addNotificationDelivery("a", NotificationDelivery("disk.low", "$n", true, 200))
                }
                store.addNotificationDelivery("b", NotificationDelivery("disk.low", "only", false, null, "IOException"))
                val kept = store.notificationDeliveries("a")
                assertEquals(NOTIFICATION_DELIVERIES_KEPT, kept.size)
                assertEquals("${NOTIFICATION_DELIVERIES_KEPT + 4}", kept.first().at, "the newest first")
                assertEquals("5", kept.last().at, "the oldest five are gone")
                assertEquals(1, store.notificationDeliveries("b").size, "another target's are untouched")
                assertEquals(setOf("a", "b"), store.lastNotificationDeliveries().keys)
                assertEquals("only", store.lastNotificationDeliveries().getValue("b").at)

                assertTrue(store.deleteNotificationTarget("a"))
                store.addNotificationDelivery("a", NotificationDelivery("disk.low", "late", true, 200))
                assertTrue(store.notificationDeliveries("a").isEmpty(), "a delivery to a removed target is not kept")
                assertEquals(listOf("b"), store.notificationTargets().map { it.id })
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
