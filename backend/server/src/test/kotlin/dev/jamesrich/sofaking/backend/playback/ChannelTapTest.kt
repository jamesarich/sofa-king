package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.lineupStack
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelTapTest {
    @Test fun `a tap keeps its session slot past the external idle lease while it reads`() = withTap {
            tap,
            sessions,
            clock,
            channel,
        ->
        val release = CompletableDeferred<Unit>()
        val reading = launch { tap.reading(channel, "tile") { release.await() } }
        withTimeout(10_000) { while (sessions.count() == 0) delay(10) }
        clock.now = clock.now.plus(SessionManager.EXTERNAL_IDLE).plusSeconds(1)
        sessions.expire()
        assertEquals(1, sessions.count(), "the lease ran out under a tap that is still reading")
        release.complete(Unit)
        reading.join()
        assertEquals(0, sessions.count(), "the session is released when the read ends")
    }

    @Test fun `a session ended under a tap fails the read for its caller to reopen`() = withTap {
            tap,
            sessions,
            _,
            channel,
        ->
        val started = CompletableDeferred<Unit>()
        val reading = async {
            runCatching {
                tap.reading(channel, "tile") {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        assertTrue(sessions.release(sessions.snapshot().single().id))
        val failure = withTimeout(10_000) { reading.await() }.exceptionOrNull()
        assertEquals(Wire.ErrorCode.SESSION_EXPIRED, (failure as ApiFailure).code)
    }

    private fun withTap(block: suspend CoroutineScope.(ChannelTap, SessionManager, MutableClock, String) -> Unit) =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-tap-")
            try {
                Fixture().use { fixture ->
                    HttpClient(CIO).use { http ->
                        openStore(dir).use { store ->
                            val clock = MutableClock()
                            val policy = UpstreamPolicy(allowLoopback = true)
                            val feeds = FeedService(listOf(fixture.feed), store, http, clock, policy)
                            feeds.refresh(fixture.feed)
                            val catalog = lineupStack(feeds, store, clock, http).catalog
                            val sessions = SessionManager(catalog, store, clock, 1)
                            val relay = LoopbackRelay(http, policy)
                            try {
                                val channel = catalog.catalog().channels.single().id
                                block(ChannelTap(sessions, feeds, relay), sessions, clock, channel)
                            } finally {
                                relay.close()
                                sessions.close()
                            }
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
}
