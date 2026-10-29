package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.ClientCrash
import dev.jamesrich.sofaking.backend.store.addClientCrash
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminPortalCrashesTest : AdminPortalCase() {
    private val crash = ClientCrash(
        id = "c1",
        clientId = "tv-1",
        clientName = "Den TV",
        receivedAt = Instant.parse("2026-10-10T00:00:00Z").toString(),
        occurredAt = "2026-10-09T23:59:00Z",
        exception = "java.lang.IllegalStateException",
        message = "No focus target",
        frames = listOf("dev.jamesrich.sofaking.ui.guide.GuideGrid.focusRow(GuideGrid.kt:212)"),
        appVersion = "0.30.0",
        platform = "android-tv",
        osVersion = "Android 14",
        device = "Chromecast",
    )

    @Test fun `the Crashes page says when no app has reported one and needs a sign in`() = testApplication {
        portal {
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/system/crashes").status)
            browser.signIn()
            val page = browser.get("/admin/system/crashes").bodyAsText()
            assertTrue(page.contains("No app has reported a crash."), page.take(2000))
            assertTrue(page.contains("href=\"/admin/system/crashes\""), "the System tabs list it")
        }
    }

    @Test fun `the Crashes page lists a reported crash with its device and frames without scripts`() = testApplication {
        portal {
            runtime.store.addClientCrash(crash)
            browser.signIn()
            val page = browser.get("/admin/system/crashes").bodyAsText()
            assertTrue(page.contains("Den TV"), page.take(3000))
            assertTrue(page.contains("java.lang.IllegalStateException"))
            assertTrue(page.contains("GuideGrid.focusRow(GuideGrid.kt:212)"))
            assertTrue(page.contains("0.30.0"))
        }
    }

    @Test fun `a crash from the last day is an attention item and an older one is not`() = testApplication {
        portal {
            // The portal runs on a test clock that stands at this instant.
            val now = Instant.parse("2026-10-10T13:00:00Z")
            runtime.store.addClientCrash(crash.copy(id = "old", receivedAt = now.minusSeconds(3 * 86_400).toString()))
            browser.signIn()
            assertFalse(browser.get("/admin").bodyAsText().contains("app crash"), "an old crash needs no attention")

            runtime.store.addClientCrash(crash.copy(id = "new", receivedAt = now.toString()))
            val home = browser.get("/admin").bodyAsText()
            assertTrue(home.contains("1 app crash reported in the last day"), home.take(4000))
            assertTrue(home.contains("href=\"/admin/system/crashes\""))
        }
    }
}
