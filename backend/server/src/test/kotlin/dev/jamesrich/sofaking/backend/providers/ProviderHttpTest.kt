package dev.jamesrich.sofaking.backend.providers

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class ProviderHttpTest {
    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test fun `gzip that inflates past the cap is refused as a provider failure`() {
        val bomb = gzip(ByteArray(1024 * 1024))
        assertTrue(bomb.size < 16 * 1024, "the fixture must be small compressed")
        assertFailsWith<ProviderFailure> { ProviderHttp.gunzipped(bomb, 64 * 1024) }
    }

    @Test fun `gzip that inflates within the cap is returned whole`() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        assertContentEquals(payload, ProviderHttp.gunzipped(gzip(payload), 64 * 1024))
    }
}
