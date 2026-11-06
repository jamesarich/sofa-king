package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.CatalogService
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.lineupStack
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.UpdatePlayback
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A recording that finds every playback slot taken, and the app sessions it may take one from. */
class SessionPreemptionTest {
    @Test fun `a recording takes the slot of the stalest watching app session when the pool is full`() =
        withChannel("sofa-preempt-") { catalog, store, clock, channel ->
            val sessions = SessionManager(catalog, store, clock, 4)
            val heard = mutableListOf<Pair<String, String>>()
            sessions.onPreempted = { watching, recording -> heard += watching to recording }
            val watching = (1..4).map { n ->
                sessions.open(OpenPlayback(channel, "watch", "w$n")).also { clock.now = clock.now.plusSeconds(1) }
            }
            // The first viewer renews, so the second is now the stalest.
            sessions.update(watching[0].id, UpdatePlayback())
            val recording = sessions.external.open(channel, "DVR", shareable = false, preempt = true)
            assertEquals(4, sessions.count())
            assertEquals(
                Wire.ErrorCode.SESSION_EXPIRED,
                assertFailsWith<ApiFailure> { sessions.get(watching[1].id) }.code,
            )
            for (kept in listOf(watching[0], watching[2], watching[3])) assertEquals(kept.id, sessions.get(kept.id).id)
            assertTrue(sessions.snapshot().any { it.id == recording.id && it.external })
            assertEquals(listOf(channel to channel), heard)
            sessions.close()
        }

    @Test fun `with preemption off a recording finds the pool full as before and nobody loses a slot`() =
        withChannel("sofa-nopreempt-") { catalog, store, clock, channel ->
            val sessions = SessionManager(catalog, store, clock, 4)
            val watching = (1..4).map { sessions.open(OpenPlayback(channel, "watch", "w$it")) }
            assertEquals(
                Wire.ErrorCode.CAPACITY_EXHAUSTED,
                assertFailsWith<ApiFailure> { sessions.external.open(channel, "DVR", shareable = false) }.code,
            )
            for (kept in watching) assertEquals(kept.id, sessions.get(kept.id).id)
            sessions.close()
        }

    @Test fun `a recording never takes the slot of another recording`() =
        withChannel("sofa-preempt-recordings-") { catalog, store, clock, channel ->
            val sessions = SessionManager(catalog, store, clock, 2)
            sessions.open(OpenPlayback(channel, "watch", "viewer"))
            val first = sessions.external.open(channel, "DVR", shareable = false, preempt = true)
            sessions.media.attach(first.id, Job())
            val second = sessions.external.open(channel, "DVR", shareable = false, preempt = true)
            sessions.media.attach(second.id, Job())
            assertEquals(
                Wire.ErrorCode.CAPACITY_EXHAUSTED,
                assertFailsWith<ApiFailure> {
                    sessions.external.open(channel, "DVR", shareable = false, preempt = true)
                }.code,
            )
            assertEquals(setOf(first.id, second.id), sessions.snapshot().map { it.id }.toSet())
            sessions.close()
        }

    private fun withChannel(
        prefix: String,
        block: suspend (CatalogService, BackendStore, MutableClock, String) -> Unit,
    ) = runBlocking<Unit> {
        val dir = Files.createTempDirectory(prefix)
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.refresh(fixture.feed)
                        val catalog = lineupStack(feeds, store, clock, http).catalog
                        block(catalog, store, clock, catalog.catalog().channels.single().id)
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
