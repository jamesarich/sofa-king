package dev.jamesrich.sofaking.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PackageDownloadTest {
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    private val file = File.createTempFile("update", ".apk").apply { deleteOnExit() }

    @Test
    fun `the package lands in the file with its digest and progress`() = runTest {
        val http =
            FakeHttp(this) {
                respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
            }
        val progress = mutableListOf<Int?>()
        assertEquals(sha256, downloadPackage(http.client, "${FAKE_BASE}app.apk", file) { progress += it })
        assertContentEquals(bytes, file.readBytes())
        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
        assertEquals(progress, progress.distinct())
    }

    @Test
    fun `a response without a length reports no percent`() = runTest {
        val http = FakeHttp(this) { respond(bytes, HttpStatusCode.OK) }
        val progress = mutableListOf<Int?>()
        assertEquals(sha256, downloadPackage(http.client, "${FAKE_BASE}app.apk", file) { progress += it })
        assertEquals(listOf<Int?>(null), progress)
    }

    @Test
    fun `an unsuccessful status throws and leaves no file`() = runTest {
        val http = FakeHttp(this) { respond("gone", HttpStatusCode.NotFound) }
        assertFailsWith<IOException> { downloadPackage(http.client, "${FAKE_BASE}app.apk", file) {} }
        assertFalse(file.exists())
    }

    @Test
    fun `a package goes to the installer only when every check passes`() {
        fun verdict(expected: String? = sha256, name: String? = "app", code: Long = 2, sameKey: Boolean? = true) =
            packageVerdict(expected, sha256, name, "app", code, installedVersionCode = 1, sameKey = sameKey)
        assertNull(verdict())
        assertNull(verdict(expected = null))
        assertNull(verdict(sameKey = null))
        assertEquals(InstallFailure.CHECKSUM, verdict(expected = "0".repeat(64)))
        assertEquals(InstallFailure.NOT_THIS_APP, verdict(name = null))
        assertEquals(InstallFailure.NOT_THIS_APP, verdict(name = "other"))
        assertEquals(InstallFailure.DIFFERENT_KEY, verdict(sameKey = false))
        assertEquals(InstallFailure.NOT_NEWER, verdict(code = 1))
    }
}
