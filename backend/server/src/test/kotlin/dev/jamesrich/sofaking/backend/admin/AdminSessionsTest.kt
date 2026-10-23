package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.adminSessions
import dev.jamesrich.sofaking.backend.store.openStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SameSecretTest {
    @Test fun `only the identical string matches, whatever its length`() {
        val expected = "x".repeat(32) + "yz"
        assertTrue(sameSecret(expected, expected))
        assertFalse(sameSecret(null, expected))
        assertFalse(sameSecret("", expected))
        assertFalse(sameSecret(expected.dropLast(1), expected), "a prefix is not the secret")
        assertFalse(sameSecret(expected + "0", expected), "nor is an extension")
        assertFalse(sameSecret(expected.uppercase(), expected), "nor a case change")
        assertFalse(sameSecret("X".repeat(34), expected))
        assertTrue(sameSecret("", ""))
    }
}

class AdminSessionsTest {
    private val dir: Path = Files.createTempDirectory("sofa-admin-sessions-")
    private val secret = "admin".repeat(8)

    @AfterTest fun cleanUp() { dir.toFile().deleteRecursively() }

    @Test fun `a stored session survives a restart, and the database holds its hash, never the cookie's ID`() = runBlocking<Unit> {
        val clock = MutableClock()
        val opened = openStore(dir).use { store -> AdminSessions(clock, secret, store).open() }
        openStore(dir).use { store ->
            val restarted = AdminSessions(clock, secret, store)
            val found = assertNotNull(restarted.find(opened.cookie), "the new process finds the session")
            assertEquals(opened.session.csrf, found.csrf)
            assertNull(restarted.find(opened.cookie + "x"))
            assertTrue(store.adminSessions(clock.instant()).none { it.key == opened.cookie }, "only the hash is stored")
        }
        for (file in Files.list(dir).use { it.toList() }.filter { it.fileName.toString().startsWith("backend.sqlite") }) {
            assertFalse(Files.readAllBytes(file).decodeToString().contains(opened.cookie), "the ID is nowhere in ${file.fileName}")
        }
    }

    @Test fun `sign-out and expiry end a session for the next process too`() = runBlocking<Unit> {
        val clock = MutableClock()
        openStore(dir).use { store ->
            val sessions = AdminSessions(clock, secret, store, lifetime = Duration.ofHours(12))
            val out = sessions.open()
            val kept = sessions.open()
            sessions.close(out.cookie)
            assertNull(sessions.find(out.cookie))
            assertNull(AdminSessions(clock, secret, store).find(out.cookie), "signed out is signed out after a restart")
            clock.now = clock.now.plus(Duration.ofHours(13))
            assertNull(AdminSessions(clock, secret, store).find(kept.cookie), "an expired session is not loaded")
            assertTrue(store.adminSessions(clock.instant()).isEmpty())
        }
    }

    @Test fun `past the limit the session closest to expiry gives way, in the store too`() = runBlocking<Unit> {
        val clock = MutableClock()
        openStore(dir).use { store ->
            val sessions = AdminSessions(clock, secret, store, limit = 2)
            val first = sessions.open()
            clock.now = clock.now.plusSeconds(60)
            val second = sessions.open()
            clock.now = clock.now.plusSeconds(60)
            val third = sessions.open()
            assertNull(sessions.find(first.cookie))
            val restarted = AdminSessions(clock, secret, store)
            assertNull(restarted.find(first.cookie))
            assertNotNull(restarted.find(second.cookie))
            assertNotNull(restarted.find(third.cookie))
        }
    }

    @Test fun `without a store sessions live in memory`() = runBlocking<Unit> {
        val clock = MutableClock()
        val sessions = AdminSessions(clock, secret)
        val opened = sessions.open()
        assertNotNull(sessions.find(opened.cookie))
        assertNull(AdminSessions(clock, secret).find(opened.cookie), "a new process starts with none")
    }

    @Test fun `a changed or removed admin token ends the sessions the old one opened`() = runBlocking<Unit> {
        val clock = MutableClock()
        openStore(dir).use { store ->
            val opened = AdminSessions(clock, secret, store).open()
            assertNull(AdminSessions(clock, "rotated".repeat(8), store).find(opened.cookie), "a rotated token signs everyone out")
            assertNull(AdminSessions(clock, null, store).find(opened.cookie), "with no token nobody is signed in")
            assertNotNull(AdminSessions(clock, secret, store).find(opened.cookie), "the same token keeps the session")
        }
    }
}
