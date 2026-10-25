package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.admin.missedWhy
import dev.jamesrich.sofaking.backend.dvr.StoredRecording
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.contract.CreateRecordingRule
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminPortalWantedTest : AdminPortalCase() {
    @Test fun `Wanted lists the recent failures with why, and nothing older`() = testApplication {
        portal {
            val recent = Instant.parse("2026-10-09T20:00:00Z")
            val old = Instant.parse("2026-09-01T20:00:00Z")
            for ((id, at, title) in listOf(
                Triple("rec-new", recent, "Late Show"),
                Triple("rec-old", old, "Old Show"),
            )) {
                runtime.store.addRecording(
                    StoredRecording(
                        id, null, "c", "Channel C", at, at.plusSeconds(1800), Duration.ZERO, Duration.ZERO, title,
                        state = Wire.RecordingState.FAILED, reason = "MISSED", createdAt = at, updatedAt = at,
                    ),
                )
            }
            browser.signIn()
            val page = browser.get("/admin/recordings/wanted").bodyAsText()
            assertTrue(page.contains("Late Show") && page.contains(missedWhy("MISSED")), page)
            assertFalse(page.contains("Old Show"), "a failure older than two weeks is left out")
            assertTrue(page.contains("Every scheduled recording has a slot."))
            assertTrue(page.contains("aria-current=\"page\">Wanted<"), "the Wanted tab is marked")
        }
    }

    @Test fun `Put the rule first raises it above every other rule, and never lowers it`() = testApplication {
        portal {
            val dvr = runtime.dvr!!
            val news = dvr.rules.create(CreateRecordingRule("News", priority = 5))
            val movies = dvr.rules.create(CreateRecordingRule("Movies", priority = 20))
            browser.signIn()
            val csrf = field(browser.get("/admin/recordings/wanted").bodyAsText(), "csrf")

            val moved = browser.post("/admin/recordings/wanted/rules/${news.id}/first", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, moved.status)
            assertEquals(21, dvr.rules.list().single { it.id == news.id }.priority)

            browser.post("/admin/recordings/wanted/rules/${news.id}/first", "csrf" to csrf)
            assertEquals(21, dvr.rules.list().single { it.id == news.id }.priority, "a rule already first stays")
            assertEquals(20, dvr.rules.list().single { it.id == movies.id }.priority)
        }
    }

    @Test fun `each failure reason reads in words`() {
        assertTrue(missedWhy("DISK_FULL").contains("disk filled up"))
        assertTrue(missedWhy("NO_MEDIA").contains("sent nothing"))
        assertTrue(missedWhy("UPSTREAM_502").contains("UPSTREAM_502"))
        assertEquals("It failed", missedWhy(null))
    }
}
