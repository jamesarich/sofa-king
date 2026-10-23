package dev.jamesrich.sofaking.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** The address every fake server answers on. */
internal const val FAKE_BASE = "http://sofa.test/"

/** A request as the fake server saw it: `METHOD /path body`, the body only when there is one, and its encoded [query]. */
internal data class SeenRequest(
    val method: String,
    val path: String,
    val body: String,
    val headers: Map<String, String?>,
    val query: String = "",
) {
    override fun toString() = "$method $path $body".trim()
}

/**
 * An [HttpClient] over Ktor's [MockEngine] that runs on the test's scheduler, so delays are virtual and requests never
 * run in parallel; every request is recorded in [requests] before [handler] answers it. The APIs from [api] decode on
 * the same scheduler.
 */
internal class FakeHttp(
    scope: TestScope,
    private val handler: suspend MockRequestHandleScope.(SeenRequest) -> HttpResponseData,
) {
    val requests = mutableListOf<SeenRequest>()

    private val dispatcher = StandardTestDispatcher(scope.testScheduler)

    val client = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = this@FakeHttp.dispatcher
                addHandler { request -> handler(record(request)) }
            },
        ),
    ) { expectSuccess = false }

    fun api(token: String = "secret", base: String = FAKE_BASE) = SofaKingApi(base, token, client.engine, dispatcher)

    fun requestsOfKind(method: String) = requests.filter { it.method == method }.map { it.toString() }

    private fun record(request: HttpRequestData): SeenRequest {
        val body = when (val content = request.body) {
            is TextContent -> content.text
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            else -> ""
        }
        // The body's content type travels on the content, not in the request headers.
        val headers = request.headers.names().associateWith { request.headers[it] } +
            (HttpHeaders.ContentType to request.body.contentType?.toString())
        return SeenRequest(
            request.method.value,
            request.url.encodedPath,
            body,
            headers,
            request.url.encodedQuery,
        ).also {
            requests +=
                it
        }
    }
}

internal fun MockRequestHandleScope.json(body: String, status: Int = 200, etag: String? = null): HttpResponseData {
    val headers = buildList {
        add(HttpHeaders.ContentType to listOf("application/json"))
        if (etag != null) add(HttpHeaders.ETag to listOf(etag))
    }
    return respond(body, HttpStatusCode.fromValue(status), headersOf(*headers.toTypedArray()))
}

internal fun MockRequestHandleScope.apiError(status: Int, code: String) =
    json("""{"code":"$code","message":"$code","retryable":false}""", status)
