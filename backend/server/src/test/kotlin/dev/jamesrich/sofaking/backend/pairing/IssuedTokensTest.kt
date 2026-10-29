package dev.jamesrich.sofaking.backend.pairing

import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IssuedTokensTest {
    /** Runs [onRead] once, on the first clock read after [armed], which is how a revoke lands mid-resolve. */
    private class HookedClock : Clock() {
        var armed = false
        var onRead: () -> Unit = {}

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant {
            if (armed) {
                armed = false
                onRead()
            }
            return Instant.parse("2026-10-10T13:00:00Z")
        }
    }

    @Test fun `a token revoked while a request resolves it does not authenticate again`() = runBlocking {
        val dir = Files.createTempDirectory("issued-tokens")
        val store = openStore(dir)
        try {
            val clock = HookedClock()
            val tokens = IssuedTokens(store, clock)
            val secret = tokens.pair("tv", "Living room")
            clock.onRead = { runBlocking { tokens.revokeClient("tv") } }
            clock.armed = true

            assertNotNull(tokens.resolve(secret), "the request that raced the revoke was valid when it started")
            assertNull(tokens.resolve(secret))
        } finally {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }
}
