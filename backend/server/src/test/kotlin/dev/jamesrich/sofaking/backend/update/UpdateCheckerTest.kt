package dev.jamesrich.sofaking.backend.update

import dev.jamesrich.sofaking.backend.jobs.Jobs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val index = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[],""" +
        """"annotations":{"org.opencontainers.image.revision":"def5678","org.opencontainers.image.version":"1.4.0"}}"""

    /** A registry that challenges the first manifest request, issues an anonymous token and then serves the index. */
    private fun registry(authorized: MutableList<String?> = mutableListOf()) = MockEngine { request ->
        val auth = request.headers[HttpHeaders.Authorization]
        when {
            request.url.host == "ghcr.io" && request.url.encodedPath == "/token" -> {
                assertEquals("ghcr.io", request.url.parameters["service"])
                assertEquals("repository:jamesarich/sofa-king:pull", request.url.parameters["scope"])
                respond(
                    """{"token":"anon-token"}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }

            request.url.encodedPath == "/v2/jamesarich/sofa-king/manifests/main" -> {
                authorized += auth
                assertTrue(request.headers[HttpHeaders.Accept]!!.contains("application/vnd.oci.image.index.v1+json"))
                if (auth == null) {
                    respond(
                        """{"errors":[{"code":"UNAUTHORIZED"}]}""",
                        HttpStatusCode.Unauthorized,
                        headersOf(
                            HttpHeaders.WWWAuthenticate,
                            """Bearer realm="https://ghcr.io/token",service="ghcr.io",scope="repository:jamesarich/""" +
                                """sofa-king:pull"""",
                        ),
                    )
                } else {
                    respond(
                        index,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/vnd.oci.image.index.v1+json"),
                            "Docker-Content-Digest" to listOf("sha256:abc"),
                        ),
                    )
                }
            }

            else -> respond("not here", HttpStatusCode.NotFound)
        }
    }

    @Test fun `the check answers the bearer challenge and reads the index annotations`() = runTest {
        val dir = Files.createTempDirectory("sofa-update-")
        val asked = mutableListOf<String?>()
        val jobs = Jobs(clock)
        HttpClient(registry(asked)).use { http ->
            val checker =
                UpdateChecker(
                    http,
                    clock,
                    dir,
                    "ghcr.io/jamesarich/sofa-king:main",
                    RunningBuild("abc1234", "1.3.0"),
                    jobs,
                )
            val state = checker.check()
            assertEquals(listOf(null, "Bearer anon-token"), asked)
            assertEquals("def5678", state.published?.revision)
            assertEquals("1.4.0", state.published?.version)
            assertEquals("sha256:abc", state.published?.digest)
            assertEquals(false, state.current)
            assertNull(state.error)
            assertEquals(clock.instant(), state.checkedAt)
            val job = jobs.snapshot().single { it.id == UpdateChecker.JOB }
            assertEquals(true, job.lastOk)
            assertEquals("update available: def5678", job.lastNote)
            assertTrue(job.canRunNow)
        }
        HttpClient(registry()).use { http ->
            val state = UpdateChecker(
                http,
                clock,
                dir,
                "ghcr.io/jamesarich/sofa-king:main",
                RunningBuild("def5678", "1.4.0"),
                Jobs(clock),
            ).check()
            assertEquals(true, state.current)
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `a newer build asks an installed updater once, and never for one it refused`() = runTest {
        val dir = Files.createTempDirectory("sofa-update-")
        val request = dir.resolve(UpdateChecker.REQUEST_FILE)
        fun report(outcome: String, candidate: String) = Files.writeString(
            dir.resolve(UpdateChecker.STATUS_FILE),
            """{"checkedAt":"2026-10-21T17:00:""" +
                """00Z","running":"abc1234","candidate":"$candidate","outcome":"$outcome"}""",
        )
        HttpClient(registry()).use { http ->
            val jobs = Jobs(clock)
            val checker =
                UpdateChecker(
                    http,
                    clock,
                    dir,
                    "ghcr.io/jamesarich/sofa-king:main",
                    RunningBuild("abc1234", "1.3.0"),
                    jobs,
                )
            checker.check()
            assertFalse(Files.exists(request), "no updater has reported, so none is installed")
            report("up-to-date", "abc1234")
            checker.check()
            assertTrue(Files.exists(request))
            assertEquals(
                "update available: def5678; asked the updater to install it",
                jobs.snapshot().single().lastNote,
            )
            Files.delete(request)
            checker.check()
            assertFalse(Files.exists(request), "asked once per revision")
        }
        HttpClient(registry()).use { http ->
            report("rolled-back", "def5678")
            UpdateChecker(
                http,
                clock,
                dir,
                "ghcr.io/jamesarich/sofa-king:main",
                RunningBuild("abc1234", "1.3.0"),
                Jobs(clock),
            ).check()
            assertFalse(Files.exists(request), "a build the updater rolled back is not asked for again")
            UpdateChecker(
                http,
                clock,
                dir,
                "ghcr.io/jamesarich/sofa-king:main",
                RunningBuild("def5678", "1.4.0"),
                Jobs(clock),
            ).check()
            assertFalse(Files.exists(request), "the running build")
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `a registry failure is recorded, not thrown, and names no URL`() = runTest {
        val dir = Files.createTempDirectory("sofa-update-")
        HttpClient(MockEngine { respond("down", HttpStatusCode.ServiceUnavailable) }).use { http ->
            val jobs = Jobs(clock)
            val state = UpdateChecker(
                http,
                clock,
                dir,
                "ghcr.io/jamesarich/sofa-king:main",
                RunningBuild("abc1234", "1.3.0"),
                jobs,
            ).check()
            assertNull(state.published)
            assertNull(state.current)
            assertNotNull(state.error)
            assertTrue(state.error!!.contains("503"), state.error)
            assertFalse(state.error!!.contains("ghcr.io"))
            assertEquals(false, jobs.snapshot().single().lastOk)
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `the updater's report is read and the request file written in the data directory`() = runTest {
        val dir = Files.createTempDirectory("sofa-update-")
        Files.writeString(
            dir.resolve(UpdateChecker.STATUS_FILE),
            """{"checkedAt":"2026-10-16T11:50:00Z","image":"ghcr.io/jamesarich/sofa-king:""" +
                """main","running":"abc1234","candidate":"abc1234","outcome":"up-to-date","message":"running """ +
                """abc1234"}""",
        )
        HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }).use { http ->
            val checker = UpdateChecker(http, clock, dir, null, RunningBuild("abc1234", "1.3.0"), Jobs(clock))
            val before = checker.state()
            assertNull(before.image, "an empty image turns the registry check off")
            assertEquals("up-to-date", before.updater?.outcome)
            assertEquals("2026-10-16T11:50:00Z", before.updater?.checkedAt)
            assertNull(before.requestedAt)
            assertTrue(checker.requestUpdate())
            assertEquals(clock.instant().toString(), Files.readString(dir.resolve(UpdateChecker.REQUEST_FILE)))
            assertEquals(clock.instant(), checker.state().requestedAt)
            assertEquals(before.published, checker.check().published, "check without an image fetches nothing")
        }
        Files.writeString(dir.resolve(UpdateChecker.STATUS_FILE), "not json")
        HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }).use { http ->
            assertNull(
                UpdateChecker(http, clock, dir, null, RunningBuild("abc1234", "1.3.0"), Jobs(clock)).state().updater,
                "a broken report reads as none",
            )
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `image references name a registry host, a repository and a tag`() {
        assertEquals(
            UpdateChecker.ImageRef("ghcr.io", "jamesarich/sofa-king", "main"),
            UpdateChecker.ImageRef.parse("ghcr.io/jamesarich/sofa-king:main"),
        )
        assertEquals(
            UpdateChecker.ImageRef("localhost:5000", "sofa", "latest"),
            UpdateChecker.ImageRef.parse("localhost:5000/sofa"),
        )
        assertEquals(
            UpdateChecker.ImageRef("registry-1.docker.io", "library/busybox", "1.38"),
            UpdateChecker.ImageRef.parse("busybox:1.38"),
        )
        assertEquals(
            UpdateChecker.ImageRef("registry-1.docker.io", "jamesarich/sofa-king", "main"),
            UpdateChecker.ImageRef.parse("jamesarich/sofa-king:main"),
        )
        for (bad in listOf("ghcr.io/jamesarich/sofa-king@sha256:abc", "ghcr.io/:main", "ghcr.io/x:bad tag")) {
            val failure = runCatching { UpdateChecker.ImageRef.parse(bad) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException, bad)
        }
    }
}
