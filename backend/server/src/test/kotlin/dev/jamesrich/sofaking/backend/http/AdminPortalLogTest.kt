package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.logging.logFile
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminPortalLogTest : AdminPortalCase() {
    @Test fun `the Events page downloads the log file once there is one`() = testApplication {
        portal {
            browser.signIn()
            assertEquals(HttpStatusCode.NotFound, browser.get("/admin/system/events/server.log").status)
            assertTrue(browser.get("/admin/system/events").bodyAsText().contains("writes no log file"))

            val file = logFile(dataDirectory)
            Files.createDirectories(file.parent)
            Files.writeString(file, "2026-10-10T13:00:00.000Z INFO  [main] Main - started\n")
            val page = browser.get("/admin/system/events").bodyAsText()
            assertTrue(page.contains("href=\"/admin/system/events/server.log\""), page.take(2000))
            val download = browser.get("/admin/system/events/server.log")
            assertEquals(HttpStatusCode.OK, download.status)
            assertTrue(download.headers[HttpHeaders.ContentDisposition].orEmpty().contains("sofa-king.log"))
            assertTrue(download.bodyAsText().contains("Main - started"))
        }
    }

    @Test fun `debug logging starts for half an hour and stops on request`() = testApplication {
        portal {
            browser.signIn()
            val page = browser.get("/admin/system/events").bodyAsText()
            assertTrue(page.contains("Debug for 30 minutes"), page.take(2000))
            val csrf = field(page, "csrf")
            val window = runtime.debugWindow!!
            try {
                assertEquals(
                    HttpStatusCode.SeeOther,
                    browser.post(
                        "/admin/system/events/debug",
                        "csrf" to csrf,
                        "op" to "start",
                    ).status,
                )
                assertNotNull(window.until)
                val open = browser.get("/admin/system/events").bodyAsText()
                assertTrue(open.contains("Logging at DEBUG for 30 minutes.") && open.contains("Stop debug logging"))

                browser.post("/admin/system/events/debug", "csrf" to csrf, "op" to "stop")
                assertNull(window.until)
                assertTrue(browser.get("/admin/system/events").bodyAsText().contains("Logging is back at INFO."))
            } finally {
                window.close()
            }
        }
    }
}
