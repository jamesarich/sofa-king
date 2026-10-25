package dev.jamesrich.sofaking.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UpdateCheckTest {
    private val release = """{"name":"Snapshot 0.4.0-dev.140+abc1234","tag_name":"snapshot","html_url":"https://github.com/jamesarich/sofa-king/releases/tag/snapshot",
        "assets":[{"name":"sofa-king-snapshot-linux-amd64.deb","browser_download_url":"https://x/deb"},{"name":"sofa-king-snapshot.apk","browser_download_url":"https://x/apk","digest":"sha256:51646D44cec723416a4c1485f4d5349462dd8d5d936f3fecaf791b2f59b82a37"}],"extra":1}"""

    @Test
    fun `the run number comes from the dev suffix`() {
        assertEquals(123, UpdateChecker.runNumber("0.4.0-dev.123+abc1234"))
        assertEquals(7, UpdateChecker.runNumber("-dev.7"))
        assertNull(UpdateChecker.runNumber("0.4.0"))
        assertNull(UpdateChecker.runNumber("0.4.0-rc.1+abc"))
    }

    @Test
    fun `the published build reads from the release`() = runTest {
        val http = FakeHttp(this) { respond(release, HttpStatusCode.OK) }
        val latest = UpdateChecker(http.client, "${FAKE_BASE}release").latest()!!
        assertEquals("0.4.0-dev.140+abc1234", latest.version)
        assertEquals(140, latest.run)
        assertEquals("https://x/apk", latest.apkUrl)
        assertEquals("51646d44cec723416a4c1485f4d5349462dd8d5d936f3fecaf791b2f59b82a37", latest.apkSha256)
        assertEquals("https://github.com/jamesarich/sofa-king/releases/tag/snapshot", latest.pageUrl)
        assertEquals("GET /release", http.requests.single().toString())
    }

    @Test
    fun `a release titled without a version reads as its tag`() = runTest {
        val untitled = release.replace("Snapshot 0.4.0-dev.140+abc1234", "Snapshot")
        val latest = UpdateChecker(
            FakeHttp(this) {
                respond(untitled, HttpStatusCode.OK)
            }.client,
            "${FAKE_BASE}release",
        ).latest()!!
        assertEquals("snapshot", latest.version)
        assertNull(latest.run)
    }

    @Test
    fun `only a sha256 digest of the right length is kept`() {
        assertNull(UpdateChecker.sha256Hex("sha512:" + "a".repeat(64)))
        assertNull(UpdateChecker.sha256Hex("a".repeat(64)))
        assertNull(UpdateChecker.sha256Hex("sha256:abc"))
        assertEquals("ab".repeat(32), UpdateChecker.sha256Hex("sha256:" + "AB".repeat(32)))
    }

    @Test
    fun `a missing release or a body that is not one reads as null`() = runTest {
        assertNull(
            UpdateChecker(
                FakeHttp(this) {
                    respond("{}", HttpStatusCode.NotFound)
                }.client,
                "${FAKE_BASE}r",
            ).latest(),
        )
        assertNull(
            UpdateChecker(
                FakeHttp(this) {
                    respond("<html>", HttpStatusCode.OK)
                }.client,
                "${FAKE_BASE}r",
            ).latest(),
        )
    }

    @Test
    fun `a newer run is an update and anything else is not`() {
        val latest = PublishedBuild("0.4.0-dev.140+abc1234", 140, "https://page", null)
        assertIs<UpdateState.Available>(UpdateChecker.compare("0.4.0-dev.139+abc", latest))
        assertIs<UpdateState.UpToDate>(UpdateChecker.compare("0.4.0-dev.140+abc", latest))
        assertIs<UpdateState.UpToDate>(UpdateChecker.compare("0.4.0-dev.141+abc", latest))
        assertIs<UpdateState.Unknown>(UpdateChecker.compare("0.4.0", latest))
        assertIs<UpdateState.Unknown>(UpdateChecker.compare("0.4.0-dev.1+a", latest.copy(run = null)))
    }

    private val notes = """Built from abc by https://run. Unsigned desktop packages; the APK uses a debug key.

## 0.4.0-dev.140+abc1234

### New

- library: Extras and More like this on an item's page (#259)
- Scrub thumbnails over the seek bar

### Fixes

- app,server: A source that fails to resolve moves to the next source (#262)

## 0.4.0-dev.138+def5678

### New

- guide: Today's programs at a glance (#250)
"""

    @Test
    fun `the notes read per build without the scope or the pull request`() {
        assertEquals(
            listOf(
                ReleaseNote(140, false, "Extras and More like this on an item's page"),
                ReleaseNote(140, false, "Scrub thumbnails over the seek bar"),
                ReleaseNote(140, true, "A source that fails to resolve moves to the next source"),
                ReleaseNote(138, false, "Today's programs at a glance"),
            ),
            UpdateChecker.releaseNotes(notes),
        )
    }

    @Test
    fun `notes outside a numbered build or a known group are skipped`() {
        assertEquals(emptyList(), UpdateChecker.releaseNotes(null))
        assertEquals(emptyList(), UpdateChecker.releaseNotes("### New\n\n- feat: before any build\n"))
        assertEquals(emptyList(), UpdateChecker.releaseNotes("## Snapshot\n\n### New\n\n- not a numbered build\n"))
        assertEquals(emptyList(), UpdateChecker.releaseNotes("## 0.4.0-dev.9+a\n\n### Other\n\n- an unknown group\n"))
        assertEquals(
            listOf(ReleaseNote(9, true, "Carriage returns are trimmed")),
            UpdateChecker.releaseNotes("## 0.4.0-dev.9+a\r\n### Fixes\r\n- Carriage returns are trimmed\r\n"),
        )
    }

    @Test
    fun `an update carries the notes of the builds newer than the running one`() = runTest {
        val body = release.replace("\"extra\":1", "\"extra\":1,\"body\":" + Json.encodeToString(notes))
        val latest = UpdateChecker(
            FakeHttp(this) {
                respond(body, HttpStatusCode.OK)
            }.client,
            "${FAKE_BASE}release",
        ).latest()!!
        assertEquals(4, latest.notes.size)
        val behindOne = assertIs<UpdateState.Available>(UpdateChecker.compare("0.4.0-dev.139+abc", latest))
        assertEquals(listOf(140), behindOne.changes.map { it.run }.distinct())
        val behindMore = assertIs<UpdateState.Available>(UpdateChecker.compare("0.4.0-dev.100+abc", latest))
        assertEquals(4, behindMore.changes.size)
    }

    @Test
    fun `a tagged build asks for the latest release and a snapshot asks for the snapshot`() = runTest {
        val http = FakeHttp(this) { respond(release, HttpStatusCode.OK) }
        val checker = UpdateChecker(http.client, "${FAKE_BASE}snapshot", "${FAKE_BASE}latest")
        checker.latest("0.4.0")
        checker.latest("0.4.0-dev.12+abc")
        assertEquals(listOf("GET /latest", "GET /snapshot"), http.requests.map { it.toString() })
    }

    @Test
    fun `a release title or tag gives the plain version`() = runTest {
        val tagged = release.replace("Snapshot 0.4.0-dev.140+abc1234", "Sofa King 0.5.0")
        val latest = UpdateChecker(
            FakeHttp(this) { respond(tagged, HttpStatusCode.OK) }.client,
            stableUrl = "${FAKE_BASE}latest",
        ).latest("0.4.0")!!
        assertEquals("0.5.0", latest.version)
        assertNull(latest.run)
        val untitled = release.replace("Snapshot 0.4.0-dev.140+abc1234", "").replace("\"snapshot\"", "\"v0.5.1\"")
        assertEquals(
            "0.5.1",
            UpdateChecker(
                FakeHttp(this) { respond(untitled, HttpStatusCode.OK) }.client,
                stableUrl = "${FAKE_BASE}latest",
            ).latest("0.4.0")!!.version,
        )
    }

    @Test
    fun `a newer release is an update for a tagged build with all its notes`() {
        val note = ReleaseNote(0, false, "Watch from start")
        val latest = PublishedBuild("0.10.0", null, "https://page", null, notes = listOf(note))
        assertEquals(UpdateState.Available(latest, listOf(note)), UpdateChecker.compare("0.9.2", latest))
        assertIs<UpdateState.UpToDate>(UpdateChecker.compare("0.10.0", latest))
        assertIs<UpdateState.UpToDate>(UpdateChecker.compare("1.0.0", latest))
        assertIs<UpdateState.Unknown>(UpdateChecker.compare("0.9.2", latest.copy(version = "0.10.0-dev.3+a")))
    }

    @Test
    fun `a release's notes read under its version with upgrade notes as new`() {
        val body = """Highlights written by hand.

## 0.5.0

### Upgrade notes

- dvr: Recordings move to one folder per show (#300)

### New

- Watch from start (#301)

### Fixes

- A fix (#302)
"""
        assertEquals(
            listOf(
                ReleaseNote(0, false, "Recordings move to one folder per show"),
                ReleaseNote(0, false, "Watch from start"),
                ReleaseNote(0, true, "A fix"),
            ),
            UpdateChecker.releaseNotes(body),
        )
    }
}
