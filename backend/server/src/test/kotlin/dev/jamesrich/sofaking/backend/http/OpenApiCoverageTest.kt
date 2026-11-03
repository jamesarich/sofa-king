package dev.jamesrich.sofaking.backend.http

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares the routes the application installs with the hand-kept OpenAPI document, both ways, so a route the
 * document lacks and a documented path that no longer exists each fail.
 */
class OpenApiCoverageTest {
    private val apiToken = "api".repeat(12)
    private val adminToken = "admin".repeat(8)
    private val param = Regex("\\{[^}]+}")
    private val methods = listOf("get", "put", "post", "delete", "patch", "head", "options")

    /** Installed routes the document leaves out on purpose, each a path pattern and why. */
    private val allowed: List<Pair<Regex, String>> = listOf(
        Regex("/admin(/.*)?") to
            "the portal's HTML pages and form actions; the document is the one portal route the OpenAPI document names",
    )

    private fun Endpoint.isAllowed() = path != DOCUMENT_PATH && allowed.any { (pattern, _) -> pattern.matches(path) }

    @Test fun `the OpenAPI document and the installed routes name the same operations`() = testApplication {
        sweepRoutes(apiToken, adminToken) {
            assertEquals(
                HttpStatusCode.SeeOther,
                browser.submitForm("/admin/login", parameters { append("token", adminToken) }).status,
            )
            val response = browser.get(DOCUMENT_PATH)
            assertEquals(HttpStatusCode.OK, response.status)
            val paths = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("paths").jsonObject

            val documented = LinkedHashSet<Endpoint>()
            val unsummarized = ArrayList<String>()
            for ((path, item) in paths) {
                for ((name, operation) in item.jsonObject) {
                    if (name !in methods) continue
                    val endpoint = Endpoint(HttpMethod.parse(name.uppercase()), path.replace(param, "{x}"))
                    documented += endpoint
                    if (operation.jsonObject["summary"]?.jsonPrimitive?.content.isNullOrBlank()) {
                        unsummarized += "$endpoint"
                    }
                }
            }
            val installed = endpoints.toSet()
            val undocumented = installed.filter { it !in documented && !it.isAllowed() }.sortedBy { it.toString() }
            val stale = documented.filter { it !in installed }.sortedBy { it.toString() }

            assertTrue(
                undocumented.isEmpty() && stale.isEmpty(),
                "undocumented routes:\n${undocumented.joinToString("\n")}\nstale paths:\n${stale.joinToString("\n")}",
            )
            assertTrue(documented.size > 50, "only ${documented.size} documented operations")
            assertTrue(unsummarized.isEmpty(), "operations without a summary:\n${unsummarized.joinToString("\n")}")
        }
    }

    private companion object {
        const val DOCUMENT_PATH = "/admin/api/openapi.json"
    }
}
