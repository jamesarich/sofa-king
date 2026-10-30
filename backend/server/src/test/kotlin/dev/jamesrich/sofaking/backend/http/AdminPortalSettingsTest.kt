package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.SecretOrigin
import dev.jamesrich.sofaking.backend.admin.HIDDEN_TOKEN
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.main
import dev.jamesrich.sofaking.backend.settings.Settings
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.query
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.content
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.set
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings, Plex, Library token and about pages. */
class AdminPortalSettingsTest : AdminPortalCase() {
    @Test fun `the Plex page allows and removes tuner addresses, and lists the ones refused`() = testApplication {
        portal {
            browser.signIn()
            val open = browser.get("/admin/plex").bodyAsText()
            assertTrue(open.contains("Every address may use the tuners"), "a loopback bind with no address is open")
            val csrf = field(open, "csrf")

            assertEquals(
                HttpStatusCode.SeeOther,
                browser.post(
                    "/admin/plex/allow",
                    "csrf" to csrf,
                    "address" to "10.0.0.0/8",
                ).status,
            )
            val closed = browser.get("/admin/plex").bodyAsText()
            assertTrue(closed.contains("Allowed 10.0.0.0/8") && closed.contains("Remove 10.0.0.0/8"))
            assertEquals(HttpStatusCode.Forbidden, client.get("/discover.json").status)
            assertTrue(browser.get("/admin/plex").bodyAsText().contains("Addresses refused since the server started"))
            assertTrue(browser.get("/admin").bodyAsText().contains("The tuners refused 1 address"))

            browser.post("/admin/plex/allow", "csrf" to csrf, "address" to "plex.lan")
            assertTrue(
                browser.get(
                    "/admin/plex",
                ).bodyAsText().contains("Not allowed: Not an IP address or CIDR block: plex.lan"),
            )
            assertEquals(listOf("10.0.0.0/8"), runtime.hdhrAccess.allowed)

            browser.post("/admin/plex/allow", "csrf" to csrf, "address" to "127.0.0.1")
            assertEquals(HttpStatusCode.OK, client.get("/discover.json").status)
            assertEquals(
                HttpStatusCode.Forbidden,
                browser.post(
                    "/admin/plex/disallow",
                    "csrf" to csrf.reversed(),
                    "address" to "10.0.0.0/8",
                ).status,
            )
            browser.post("/admin/plex/disallow", "csrf" to csrf, "address" to "10.0.0.0/8")
            assertEquals(listOf("127.0.0.1"), runtime.hdhrAccess.allowed)
        }
    }

    @Test fun `the Plex page saves and deletes named outputs with the token hidden`() = testApplication {
        portal {
            browser.signIn()
            val csrf = field(browser.get("/admin/plex").bodyAsText(), "csrf")
            val save = arrayOf("csrf" to csrf, "name" to "Kids", "groups" to "Kids, Cartoons", "channels" to "702")
            assertEquals(HttpStatusCode.SeeOther, browser.post("/admin/plex/outputs/save", *save).status)
            val page = browser.get("/admin/plex").bodyAsText()
            assertTrue(page.contains("Saved the kids output."))
            assertTrue(page.contains("/output/kids/playlist.m3u?token=$HIDDEN_TOKEN"))
            assertTrue(page.contains("group Kids, group Cartoons, 702"))
            assertFalse(page.contains(outputToken))

            browser.post("/admin/plex/outputs/save", "csrf" to csrf, "name" to "stream", "channels" to "1")
            assertTrue(browser.get("/admin/plex").bodyAsText().contains("Not saved: An output name is 1 to 32"))

            val asked = browser.post("/admin/plex/outputs/delete", "csrf" to csrf, "name" to "kids")
            assertTrue(asked.bodyAsText().contains("Delete the kids output?"))
            assertEquals(1, runtime.namedOutputs.list().size)
            browser.post("/admin/plex/outputs/delete", "csrf" to csrf, "name" to "kids", "confirm" to "yes")
            assertTrue(runtime.namedOutputs.list().isEmpty())
        }
    }

    @Test fun `the Library page takes a TMDB token and refuses one that cannot be a token before asking TMDB`() =
        testApplication {
            portal {
                browser.signIn()
                val page = browser.get("/admin/library").bodyAsText()
                assertTrue(
                    page.contains("action=\"/admin/library/tmdb\"") && page.contains("TMDB API read access token"),
                )
                assertTrue(page.contains("href=\"https://www.themoviedb.org/settings/api\""), page)
                val refused = browser.post("/admin/library/tmdb", "csrf" to field(page, "csrf"), "token" to "short")
                assertEquals(HttpStatusCode.BadRequest, refused.status)
                assertTrue(
                    refused.bodyAsText().contains(
                        "Not saved: A TMDB API read access token or API key has at least 32 characters",
                    ),
                )
                assertFalse(Files.exists(dataDirectory.resolve("secrets.json")))
            }
        }

    @Test fun `settings show the effective configuration with every secret redacted`() = testApplication {
        val query = "sekret".repeat(6)
        val secrets = listOf(admin, token, outputToken, plexToken, query)
        val keyed = { f: Fixture ->
            f.tsFeed.copy(playlistUrl = "${f.tsFeed.playlistUrl}?auth=$query", headers = mapOf("X-Key" to query))
        }
        portal(basePolicy, feeds = { listOf(it.feed, it.altFeed, it.twinFeed, keyed(it)) }) {
            runtime.policy!!.apply("test")
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/settings").status)
            browser.signIn()
            val html = browser.get("/admin/settings").bodyAsText()
            for (secret in secrets) assertFalse(html.contains(secret), "a secret reached the page")
            val settings = listOf("SOFA_ADMIN_TOKEN", "SOFA_PLEX_URL", "http://127.0.0.1:32400", "policy.json")
            for (setting in settings) assertTrue(html.contains(setting), setting)
            assertTrue(html.contains("/admin/system/changes"), "Settings points at the change log")
            val changes = browser.get("/admin/system/changes").bodyAsText()
            assertTrue(
                changes.contains("Policy change log") && changes.contains("Twin Sports"),
                "the change log lists the stations the run added",
            )
            assertTrue(
                html.contains("<details class=\"advanced\" id=\"advanced\">"),
                "the startup settings fold behind Advanced",
            )
            val advanced = browser.get("/admin/settings?advanced=1").bodyAsText()
            assertTrue(
                advanced.contains(Regex("""<details class="advanced" id="advanced" open(="[^"]*")?>""")),
                "advanced=1 opens the section",
            )
            val sources = browser.get("/admin/providers").bodyAsText()
            for (secret in secrets) assertFalse(sources.contains(secret), "a secret reached the Sources page")
            assertTrue(
                sources.contains("headers: X-Key") && sources.contains("Source file"),
                "the Sources page describes the source file without its values",
            )
        }
    }

    @Test
    fun `settings say which tokens were generated, read from a file or set in the environment`() = testApplication {
        val origins = mapOf(
            "SOFA_API_TOKEN" to SecretOrigin.GENERATED,
            "SOFA_ADMIN_TOKEN" to SecretOrigin.GENERATED,
            "SOFA_OUTPUT_TOKEN" to SecretOrigin.FILE,
            "SOFA_PLEX_TOKEN" to SecretOrigin.ENVIRONMENT,
        )
        portal(show = {
            it.copy(
                secretOrigins = origins,
                secretsFile = Path.of("/config/secrets.json"),
                foundInDataDirectory = setOf("SOFA_SOURCES"),
            )
        }) {
            browser.signIn()
            val html = browser.get("/admin/settings").bodyAsText()
            fun row(name: String) = html.substringAfter(">$name<").substringBefore("class=\"setting\"")
            for (name in listOf("SOFA_API_TOKEN", "SOFA_ADMIN_TOKEN")) {
                assertTrue(row(name).contains(">generated<"), "$name: ${row(name)}")
                assertTrue(row(name).contains("Generated and stored in /config/secrets.json"), name)
            }
            assertTrue(
                row("SOFA_OUTPUT_TOKEN").contains(">set<") &&
                    row("SOFA_OUTPUT_TOKEN").contains("SOFA_OUTPUT_TOKEN_FILE"),
            )
            assertTrue(
                row("SOFA_PLEX_TOKEN").contains(">set<") && row("SOFA_PLEX_TOKEN").contains("Set in the environment"),
            )
            assertTrue(row("SOFA_SOURCES").contains("Found in SOFA_DATA_DIR"))
            for (secret in listOf(
                admin,
                token,
                outputToken,
                plexToken,
            )) {
                assertFalse(html.contains(secret), "a secret reached the page")
            }
        }
    }

    @Test fun `the about page describes the server and the support snapshot holds no secret`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            assertEquals(
                HttpStatusCode.SeeOther,
                browser.get("/admin/system/snapshot.json").status,
                "the snapshot needs the session",
            )
            browser.signIn()
            val html = browser.get("/admin/system/about").bodyAsText()
            for (text in listOf(
                "Revision",
                "FFmpeg",
                "Support snapshot",
                "&quot;takenAt&quot;",
                "Lineup policy run",
                "Video encoder",
                "Run the transcode test",
            )) {
                assertTrue(html.contains(text), text)
            }
            assertFalse(
                html.contains(
                    admin,
                ) || html.contains(token) || html.contains(outputToken) || html.contains(plexToken) ||
                    html.contains("/feed.m3u"),
            )
            val snapshot = browser.get("/admin/system/snapshot.json")
            assertEquals(HttpStatusCode.OK, snapshot.status)
            assertEquals(ContentType.Application.Json, snapshot.contentType()?.withoutParameters())
            val body = Json.parseToJsonElement(snapshot.bodyAsText()).jsonObject
            assertEquals(4, body["status"]!!.jsonObject["providers"]!!.jsonObject["total"]!!.jsonPrimitive.int)
            assertTrue(body["jobs"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "policy" })
            assertFalse(snapshot.bodyAsText().contains(admin) || snapshot.bodyAsText().contains(token))
        }
    }

    @Test
    fun `the about page shows the update picture and Update now sends the host's updater a request once confirmed`() =
        testApplication {
            portal {
                browser.signIn()
                val before = browser.get("/admin/system/about").bodyAsText()
                assertTrue(before.contains("<h2>Update</h2>"), before.take(3000))
                assertTrue(before.contains("The update check is off"), "no image, no registry check")
                assertTrue(before.contains("Has not reported"), "no status file yet")
                assertFalse(before.contains("/admin/system/update/request"), "no updater, no Update now")
                Files.writeString(
                    dataDirectory.resolve("update-status.json"),
                    """{"checkedAt":"2026-01-01T00:00:00Z","image":"ghcr.io/jamesarich/sofa-king:""" +
                        """main","running":"abc1234","candidate":"def5678","outcome":"rolled-back","message":"def5""" +
                        """67""" +
                        """8 was not healthy after 180s; running abc1234 again"}""",
                )
                val reported = browser.get("/admin/system/about").bodyAsText()
                assertTrue(reported.contains("Tried def5678 and went back to abc1234"), reported.take(4000))
                assertTrue(reported.contains("Not checking in"), "a report that old is flagged")
                assertTrue(reported.contains("def5678 was not healthy after 180s"))
                val ask = browser.post("/admin/system/update/request", "csrf" to field(reported, "csrf"))
                assertEquals(HttpStatusCode.OK, ask.status)
                val dialog = ask.bodyAsText()
                assertTrue(
                    dialog.contains("Update the server now?") && dialog.contains("backs up the database"),
                    dialog.take(4000),
                )
                assertFalse(
                    Files.exists(dataDirectory.resolve("update-requested")),
                    "nothing is written before the yes",
                )
                val yes = browser.post(
                    "/admin/system/update/request",
                    "csrf" to field(reported, "csrf"),
                    "confirm" to "yes",
                )
                assertEquals(HttpStatusCode.SeeOther, yes.status)
                assertEquals("/admin/system/about#update", yes.headers[HttpHeaders.Location])
                assertTrue(Files.isRegularFile(dataDirectory.resolve("update-requested")))
                val pending = browser.get("/admin/system/about").bodyAsText()
                assertTrue(
                    pending.contains("Asked the updater to run") && pending.contains("An update was asked for"),
                    pending.take(4000),
                )
                assertFalse(pending.contains("""action="/admin/system/update/request""""), "one request at a time")
                assertEquals(
                    HttpStatusCode.Forbidden,
                    browser.post("/admin/system/update/request", "confirm" to "yes").status,
                    "the request needs the CSRF token",
                )
            }
        }

    /** The runtime settings form as the page fills it, with [changes] made. */
    private fun runtimeFields(vararg changes: Pair<String, String>): Array<Pair<String, String>> {
        val values = mapOf(
            "SOFA_LOG_LEVEL" to "info", "SOFA_XMLTV_DAYS" to "3", "SOFA_PLEX_SYNC_MINUTES" to "60",
            "SOFA_HDHR_TUNERS" to "2", "SOFA_MAX_SESSIONS" to "4", "SOFA_RECORDINGS_FREE_GIB" to "10",
            "SOFA_RECORDINGS_KEEP_DAYS" to "0", "SOFA_LEARNED_BREAKS" to "shadow", "SOFA_AD_LISTENERS" to "6",
        )
        return (values + changes).map { it.key to it.value }.toTypedArray()
    }

    @Test fun `the runtime settings form saves only a change and only with the CSRF token`() = testApplication {
        portal(basePolicy) {
            browser.signIn()
            val page = browser.get("/admin/settings").bodyAsText()
            assertTrue(
                page.contains("Runtime settings") && page.contains("Startup settings") &&
                    input(page, "SOFA_XMLTV_DAYS", "3"),
                page,
            )
            assertTrue(
                page.contains("No changes") && page.contains("hx-post=\"/admin/settings/runtime/check\""),
                "the Save control starts disabled and the form asks on change",
            )
            assertTrue(
                Settings.runtime.all { page.contains(it.help) },
                "each runtime field carries its own help",
            )
            assertFalse(
                Regex("""<div class="name mono">SOFA_MAX_SESSIONS</div>""").containsMatchIn(page),
                "a runtime setting is not listed among the startup ones",
            )
            val csrf = field(page, "csrf")
            val check = "/admin/settings/runtime/check"
            val unchanged = browser.post(check, *runtimeFields(), "csrf" to csrf).bodyAsText()
            assertTrue(unchanged.contains("No changes") && unchanged.contains("disabled"), unchanged)
            val changed = browser.post(check, *runtimeFields("SOFA_XMLTV_DAYS" to "7"), "csrf" to csrf).bodyAsText()
            assertTrue(changed.contains(">Save<") && !changed.contains("disabled"), changed)
            assertEquals(
                HttpStatusCode.Forbidden,
                browser.post("/admin/settings/runtime", *runtimeFields(), "op" to "save").status,
                "the save needs the CSRF token",
            )
        }
    }

    @Test fun `the settings page makes a token, shows its secret once and revokes it`() = testApplication {
        portal(basePolicy) {
            browser.signIn()
            val csrf = field(browser.get("/admin/settings").bodyAsText(), "csrf")
            assertEquals(
                HttpStatusCode.Forbidden,
                browser.post("/admin/settings/tokens/create", "label" to "Homepage", "scope" to "read").status,
                "making a token needs the CSRF token",
            )
            val create = "/admin/settings/tokens/create"
            val refused = browser.post(create, "label" to "x", "scope" to "api", "csrf" to csrf)
            assertEquals(HttpStatusCode.BadRequest, refused.status, "device tokens come only from pairing")
            val made = browser.post(
                "/admin/settings/tokens/create",
                "label" to "Homepage",
                "scope" to "read",
                "csrf" to csrf,
            ).bodyAsText()
            val secret = Regex("""<code>(sk_[A-Za-z0-9_-]+)</code>""").find(made)?.groupValues?.get(1)
            assertTrue(secret != null, made)
            val token = runtime.tokens.all().single()
            assertEquals("Homepage", token.label)
            val later = browser.get("/admin/settings").bodyAsText()
            assertTrue(later.contains("Homepage") && !later.contains(secret), "the secret is shown only once")
            val asked = browser.post("/admin/settings/tokens/${token.id}/revoke", "csrf" to csrf)
            assertTrue(asked.bodyAsText().contains("Revoke the Homepage token?"))
            assertEquals(1, runtime.tokens.all().size)
            val revoked = browser.post("/admin/settings/tokens/${token.id}/revoke", "csrf" to csrf, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, revoked.status)
            assertTrue(runtime.tokens.all().isEmpty())
        }
    }

    @Test fun `a runtime setting out of range is refused and stays in the form`() = testApplication {
        portal(basePolicy) {
            browser.signIn()
            val csrf = field(browser.get("/admin/settings").bodyAsText(), "csrf")
            val refused = browser.post(
                "/admin/settings/runtime",
                *runtimeFields("SOFA_HDHR_TUNERS" to "9"),
                "op" to "save",
                "csrf" to csrf,
            )
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            val body = refused.bodyAsText()
            assertTrue(body.contains("SOFA_HDHR_TUNERS must be between 1 and SOFA_MAX_SESSIONS (4)"), body)
            assertTrue(input(body, "SOFA_HDHR_TUNERS", "9"), "the refused values stay in the form")
            assertEquals(2, runtime.settings.current.hdhrTuners)
        }
    }

    @Test fun `saved runtime settings apply at once, and a reset follows the environment again`() = testApplication {
        portal(basePolicy) {
            browser.signIn()
            val csrf = field(browser.get("/admin/settings").bodyAsText(), "csrf")
            val changes = arrayOf(
                "SOFA_XMLTV_DAYS" to "7",
                "SOFA_LOG_LEVEL" to "warn",
                "SOFA_MAX_SESSIONS" to "6",
                "SOFA_HDHR_TUNERS" to "5",
            )
            val form = runtimeFields(*changes)
            val saved = browser.post("/admin/settings/runtime", *form, "op" to "save", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals("/admin/settings#runtime", saved.headers[HttpHeaders.Location])
            with(runtime.settings.current) {
                assertEquals(7, guideDays)
                assertEquals("warn", logLevel)
                assertEquals(6, maxSessions)
                assertEquals(5, hdhrTuners)
                assertEquals(60, plexSyncMinutes)
            }
            assertEquals(changes.map { it.first }.toSet(), runtime.settings.saved)
            assertEquals(6, runtime.sessions.capacity, "the session cap follows at once")
            val after = browser.get("/admin/settings").bodyAsText()
            assertTrue(
                after.contains("Saved the runtime settings; they apply now.") &&
                    after.contains("Saved here; the environment has 3.") && input(after, "SOFA_XMLTV_DAYS", "7"),
                after,
            )
            assertTrue(
                browser.get("/admin/system/about").bodyAsText().contains("warn, text"),
                "About reports the level in use",
            )

            val reset = browser.post("/admin/settings/runtime", "op" to "reset", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, reset.status)
            assertEquals(4, runtime.settings.current.maxSessions)
            assertTrue(runtime.settings.saved.isEmpty())
            assertTrue(browser.get("/admin/settings").bodyAsText().contains("follow the environment again"))
        }
    }
}
