package dev.jamesrich.sofaking.backend.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminPortalMatchTest : AdminPortalCase() {
    @Test fun `Unmatched is a Library tab that asks for a TMDB token while lookups are off`() = testApplication {
        portal {
            browser.signIn()
            val library = browser.get("/admin/library").bodyAsText()
            assertTrue(library.contains("href=\"/admin/library/unmatched\""), "the Library page links the tab")

            val page = browser.get("/admin/library/unmatched").bodyAsText()
            assertTrue(page.contains("aria-current=\"page\">Unmatched<"), "the Unmatched tab is marked")
            assertTrue(page.contains("TMDB lookups are off"), page)

            val search = browser.get("/admin/library/items/no-such-item/match")
            assertEquals(HttpStatusCode.SeeOther, search.status)
            assertEquals("/admin/library/unmatched", search.headers[HttpHeaders.Location])

            val csrf = field(page, "csrf")
            val chose = browser.post("/admin/library/items/no-such-item/match", "csrf" to csrf, "tmdb" to "348")
            assertEquals(HttpStatusCode.SeeOther, chose.status)
        }
    }
}
