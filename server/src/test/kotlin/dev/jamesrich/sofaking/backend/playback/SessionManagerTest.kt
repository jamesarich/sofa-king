package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.*
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class SessionManagerTest {
    @Test fun `idempotency promotion capacity cancellation and lease expiry are owned`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-session-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val feeds = FeedService(listOf(fixture.feed), store, http, clock); feeds.refresh(fixture.feed)
            val sessions = SessionManager(feeds, store, clock, 1)
            val request = OpenPlayback(feeds.catalog().channels.single().id, "preview", "one")
            val opened = sessions.open(request)
            assertEquals(opened, sessions.open(request))
            assertEquals("IDEMPOTENCY_CONFLICT", assertFailsWith<ApiFailure> { sessions.open(request.copy(intent = "watch")) }.code)
            assertEquals("CAPACITY_EXHAUSTED", assertFailsWith<ApiFailure> { sessions.open(request.copy(idempotencyKey = "two")) }.code)
            val promoted = sessions.update(opened.id, UpdatePlayback("watch"))
            assertEquals("watch", promoted.intent); assertEquals(opened.descriptor, promoted.descriptor)
            val mediaJob = Job()
            sessions.acquire(opened.id, 1, opened.descriptor.url.substringAfterLast('/'), mediaJob)
            sessions.release(opened.id)
            assertTrue(mediaJob.isCancelled)
            sessions.release(opened.id)
            assertEquals("SESSION_EXPIRED", assertFailsWith<ApiFailure> { sessions.open(request) }.code)
            val second = sessions.open(request.copy(idempotencyKey = "two"))
            clock.now = clock.now.plusSeconds(91); sessions.expire()
            assertEquals("SESSION_EXPIRED", assertFailsWith<ApiFailure> { sessions.get(second.id) }.code)
            sessions.close()
        } } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `manual disable immediately blocks media and cancels its upstream request`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-disable-play-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val feeds = FeedService(listOf(fixture.feed), store, http, clock); feeds.refresh(fixture.feed)
            val sessions = SessionManager(feeds, store, clock)
            val session = sessions.open(OpenPlayback(feeds.catalog().channels.single().id, idempotencyKey = "disable"))
            val token = session.descriptor.url.substringAfterLast('/')
            val job = Job(); sessions.acquire(session.id, 1, token, job)
            store.setManuallyDisabled(feeds.offerings().single().id, true)
            sessions.healthChanged(feeds.offerings().single().id)
            assertTrue(job.isCancelled)
            assertEquals("SOURCE_UNAVAILABLE", assertFailsWith<ApiFailure> { sessions.get(session.id) }.code)
            assertTrue(job.isCancelled)
            assertFailsWith<ApiFailure> { sessions.acquire(session.id, 1, token, Job()) }
            sessions.close()
        } } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `quarantined source blocks new playback without deleting channel`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-quarantine-play-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val feeds = FeedService(listOf(fixture.feed), store, http, clock); feeds.refresh(fixture.feed)
            val offering = feeds.offerings().single()
            for (minute in listOf(0L, 5L, 10L)) {
                val at = clock.now.plusSeconds(minute * 60)
                store.recordObservation(offering.id, Observation("failure-$minute", at, false, FailureClass.OFFERING), at)
            }
            val channel = feeds.catalog().channels.single()
            assertFalse(channel.playbackEligible)
            assertEquals("SOURCE_UNAVAILABLE", assertFailsWith<ApiFailure> { SessionManager(feeds, store, clock).open(OpenPlayback(channel.id, idempotencyKey = "blocked")) }.code)
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}
