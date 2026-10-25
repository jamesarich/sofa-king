package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.store.RESTORE_FILE
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdminPortalBackupTest : AdminPortalCase() {
    @Test fun `the Backup page takes a backup, offers it for download and asks before a restore`() = testApplication {
        var restarts = 0
        portal(restart = { restarts++ }) {
            browser.signIn()
            val empty = browser.get("/admin/system/backup").bodyAsText()
            assertTrue(empty.contains("No backup yet"), "a server without backups says so")
            val csrf = field(empty, "csrf")

            assertEquals(HttpStatusCode.SeeOther, browser.post("/admin/system/backup/take", "csrf" to csrf).status)
            val page = browser.get("/admin/system/backup").bodyAsText()
            val name = Regex("""manual-\d{8}-\d{6}\.sqlite""").find(page)?.value
            assertTrue(name != null && page.contains("Backed up the database as $name."), page.take(2000))

            val download = browser.get("/admin/system/backup/files/$name")
            assertEquals(HttpStatusCode.OK, download.status)
            assertTrue(download.headers[HttpHeaders.ContentDisposition].orEmpty().contains(name))
            assertEquals("SQLite format 3", download.bodyAsBytes().copyOf(15).decodeToString())
            assertEquals(HttpStatusCode.NotFound, browser.get("/admin/system/backup/files/backend.sqlite").status)
            assertNotEquals(HttpStatusCode.OK, browser.get("/admin/system/backup/files/..%2Fbackend.sqlite").status)

            val asked = browser.post("/admin/system/backup/restore", "csrf" to csrf, "name" to name).bodyAsText()
            assertTrue(asked.contains("Restore $name?"), asked.take(2000))
            assertEquals(0, restarts, "nothing is restored before the confirmation")

            val restored = browser.post(
                "/admin/system/backup/restore",
                "csrf" to csrf,
                "name" to name,
                "confirm" to "yes",
            )
            assertTrue(restored.bodyAsText().contains("<title>Restarting"))
            assertEquals(1, restarts)
            assertTrue(dataDirectory.resolve(RESTORE_FILE).exists(), "the backup waits for the next start")
        }
    }

    @Test fun `an upload restores only with the token, the box ticked and a database in it`() = testApplication {
        var restarts = 0
        portal(restart = { restarts++ }) {
            browser.signIn()
            val csrf = field(browser.get("/admin/system/backup").bodyAsText(), "csrf")
            browser.post("/admin/system/backup/take", "csrf" to csrf)
            val backup = runtime.backups!!.list().single()
            val bytes = Files.readAllBytes(runtime.backups!!.file(backup.name)!!)

            suspend fun upload(token: String, confirm: Boolean, content: ByteArray) = browser.submitFormWithBinaryData(
                "/admin/system/backup/upload",
                formData {
                    append("csrf", token)
                    if (confirm) append("confirm", "yes")
                    append(
                        "file",
                        content,
                        Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"backup.sqlite\"") },
                    )
                },
            )

            assertEquals(HttpStatusCode.Forbidden, upload("wrong", true, bytes).status)
            assertEquals(HttpStatusCode.SeeOther, upload(csrf, false, bytes).status)
            assertEquals(HttpStatusCode.SeeOther, upload(csrf, true, "not a database".toByteArray()).status)
            assertTrue(
                browser.get("/admin/system/backup").bodyAsText().contains("The file is not an SQLite database."),
                "the refusal says why",
            )
            assertEquals(0, restarts)
            assertFalse(dataDirectory.resolve(RESTORE_FILE).exists())

            val restored = upload(csrf, true, bytes)
            assertEquals(HttpStatusCode.OK, restored.status)
            assertTrue(restored.bodyAsText().contains("Restoring the uploaded database."))
            assertEquals(1, restarts)
            assertTrue(dataDirectory.resolve(RESTORE_FILE).exists())
        }
    }
}
