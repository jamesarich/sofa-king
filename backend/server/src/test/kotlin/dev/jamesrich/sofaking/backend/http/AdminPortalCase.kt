package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.PlexConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.GuideConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.append
import io.ktor.http.cookies
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/** The admin portal over the fixture's feeds, signed in from a browser that keeps cookies. */
abstract class AdminPortalCase {
    protected val json = Json { encodeDefaults = true }
    protected val token = "secret".repeat(8)
    protected val admin = "curator".repeat(6)
    protected val outputToken = "output".repeat(6)
    protected val plexToken = "plexsecret".repeat(3)
    protected val basePolicy = """{"names":{"version":1},"health":{"requireVerified":false},""" +
        """"providers":[{"id":"fixture"},{"id":"alt"},{"id":"twin"},{"id":"ts"}],"numbering":{"floor":100}}"""

    protected class Setup(
        val policyFile: Path?,
        val runtime: LiveRuntime,
        val browser: HttpClient,
        val dataDirectory: Path,
    )

    /**
     * A runtime over the fixture's feeds, with [policy] when given, and a browser that keeps
     * cookies and never follows redirects.
     */
    protected suspend fun ApplicationTestBuilder.portal(
        policy: String? = null,
        feeds: (Fixture) -> List<FeedConfig> = { listOf(it.feed, it.altFeed, it.twinFeed, it.tsFeed) },
        guides: (Fixture) -> List<GuideConfig> = { emptyList() },
        show: (ServerConfig) -> ServerConfig = { it },
        restart: () -> Unit = {},
        block: suspend Setup.() -> Unit,
    ) {
        val dir = Files.createTempDirectory("sofa-admin-portal-")
        Fixture().use { fixture ->
            val config = configIn(dir, fixture, feeds, guides, policy)
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                config,
                MutableClock(),
                policyDebounceMillis = 3_600_000,
                probes = false,
                restart = restart,
            )
            try {
                // The portal is told of a Plex token and the output token the runtime never uses, so
                // redaction is checked against them.
                val shown =
                    show(
                        config.copy(
                            outputs = OutputConfig(outputToken),
                            plex = PlexConfig("http://127.0.0.1:32400", plexToken),
                        ),
                    )
                application {
                    backendModule(
                        store,
                        MutableClock(),
                        runtime,
                        token,
                        admin,
                        OutputConfig(outputToken),
                        config = shown,
                    )
                }
                for (provider in runtime.feeds.configs) runtime.feeds.refresh(provider)
                val extraGuides = guides(fixture).map { it.id }.toSet()
                for (guide in runtime.guides.configs.filter { it.id in extraGuides }) runtime.guides.refresh(guide)
                val browser = createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
                Setup(config.policyFile, runtime, browser, dir).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    /** The server's configuration over the sources and the policy, written into [dir]. */
    private fun configIn(
        dir: Path,
        fixture: Fixture,
        feeds: (Fixture) -> List<FeedConfig>,
        guides: (Fixture) -> List<GuideConfig>,
        policy: String?,
    ): ServerConfig {
        val sources = dir.resolve("sources.json")
        Files.writeString(
            sources,
            """{"providers":${json.encodeToString(feeds(fixture))},"guides":""" +
                """${json.encodeToString(guides(fixture))}}""",
        )
        val policyFile = policy?.let { text ->
            Files.createDirectories(dir.resolve("policy")).resolve("policy.json").also { Files.writeString(it, text) }
        }
        // No update image: the registry is never asked from a test; the update check itself is tested over a mock
        // registry.
        return ServerConfig(
            "127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin,
            policyFile = policyFile, updateImage = null,
        )
    }

    protected suspend fun HttpClient.signIn(secret: String = admin): HttpResponse = submitForm(
        "/admin/login",
        parameters {
            append("token", secret)
        },
    )

    protected fun field(html: String, name: String): String =
        Regex("""name="$name" value="([^"]*)"""").find(html)?.groupValues?.get(1) ?: fail("no $name field in the page")

    protected suspend fun HttpClient.post(path: String, vararg fields: Pair<String, String>): HttpResponse =
        submitForm(path, parameters { for ((name, value) in fields) append(name, value) })

    /** Whether the page has an input named [name] holding [value], whatever other attributes sit between. */
    protected fun input(html: String, name: String, value: String): Boolean =
        Regex("""name="$name"[^>]*value="$value"""").containsMatchIn(html)

    protected fun stationsIn(file: Path): JsonObject =
        Json.parseToJsonElement(Files.readString(file)).jsonObject["stations"]?.jsonObject ?: JsonObject(emptyMap())

    protected fun policyJson(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject
}
