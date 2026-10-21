package dev.jamesrich.sofaking.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
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
        val latest = UpdateChecker(FakeHttp(this) { respond(untitled, HttpStatusCode.OK) }.client, "${FAKE_BASE}release").latest()!!
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
        assertNull(UpdateChecker(FakeHttp(this) { respond("{}", HttpStatusCode.NotFound) }.client, "${FAKE_BASE}r").latest())
        assertNull(UpdateChecker(FakeHttp(this) { respond("<html>", HttpStatusCode.OK) }.client, "${FAKE_BASE}r").latest())
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
}
