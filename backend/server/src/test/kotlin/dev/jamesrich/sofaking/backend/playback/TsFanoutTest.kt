package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.Fixture
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
class TsFanoutTest {
    private val p = (1..6).map { Fixture.tsPacket(it) }

    private suspend fun nextData(reader: TsFanout.Reader): ByteArray {
        while (true) {
            val chunk = reader.chunks.receive()
            if (!chunk.contentEquals(TS_NULL_PACKET)) return chunk
        }
    }

    @Test fun `one upstream serves two readers and survives EOF with keepalives until reconnect`() = runTest {
        val bodies = Channel<ByteChannel>(Channel.UNLIMITED)
        var connections = 0
        val streams =
            SharedStreams(backgroundScope, FanoutConfig(keepaliveMillis = 1000, graceMillis = 10_000)) {
                testScheduler.currentTime
            }
        val connect: UpstreamConnect = { consume ->
            connections++
            consume(bodies.receive())
        }
        val a = streams.join("source", connect)
        val b = streams.join("source", connect)
        val first = ByteChannel()
        bodies.send(first)
        first.writeFully(p[0] + p[1] + p[2])
        first.flush()
        a.awaitStarted()
        b.awaitStarted()
        assertContentEquals(p[0] + p[1] + p[2], a.chunks.receive())
        assertContentEquals(p[0] + p[1] + p[2], b.chunks.receive())
        assertEquals(1, connections)
        assertEquals(1, streams.active())
        val eofAt = testScheduler.currentTime
        first.close()
        assertContentEquals(TS_NULL_PACKET, a.chunks.receive())
        assertContentEquals(TS_NULL_PACKET, b.chunks.receive())
        assertTrue(testScheduler.currentTime - eofAt <= 2000, "first keepalive within two seconds of EOF")
        val second = ByteChannel()
        bodies.send(second)
        second.writeFully(p[3] + p[4] + p[5])
        second.flush()
        assertContentEquals(p[3] + p[4] + p[5], nextData(a))
        assertContentEquals(p[3] + p[4] + p[5], nextData(b))
        assertEquals(2, connections)
        assertEquals(1, streams.active())
        a.close()
        b.close()
    }

    @Test fun `readers end after the grace period when the upstream stays down`() = runTest {
        var connections = 0
        val streams =
            SharedStreams(backgroundScope, FanoutConfig(keepaliveMillis = 1000, graceMillis = 10_000)) {
                testScheduler.currentTime
            }
        val body = ByteChannel()
        val reader = streams.join("source") { consume ->
            if (connections++ == 0) {
                consume(body)
            } else {
                error("still down")
            }
        }
        body.writeFully(p[0] + p[1] + p[2])
        body.flush()
        assertContentEquals(p[0] + p[1] + p[2], reader.chunks.receive())
        val eofAt = testScheduler.currentTime
        body.close()
        var keepalives = 0
        assertFailsWith<ClosedReceiveChannelException> {
            while (true) {
                assertContentEquals(TS_NULL_PACKET, reader.chunks.receive())
                keepalives++
            }
        }
        assertTrue(keepalives >= 8, "keepalives during grace: $keepalives")
        assertTrue(testScheduler.currentTime - eofAt >= 10_000)
        assertTrue(connections > 2, "reconnect attempts: $connections")
        assertEquals(0, streams.active())
    }

    @Test fun `first connection failure fails readers and a slow reader is dropped alone`() = runTest {
        val streams = SharedStreams(backgroundScope, FanoutConfig(readerChunks = 2)) { testScheduler.currentTime }
        val failed = streams.join("dead") { throw UpstreamStatusException(502) }
        assertFailsWith<UpstreamStatusException> { failed.awaitStarted() }
        yield()
        assertEquals(0, streams.active())
        val body = ByteChannel()
        val slow = streams.join("source") { consume -> consume(body) }
        val fast = streams.join("source") { error("unused") }
        // Sync needs three aligned packets, so the first chunk carries three; later writes are one packet each.
        val writes = listOf(p[0] + p[1] + p[2], p[3], p[4])
        for (chunk in writes) {
            body.writeFully(chunk)
            body.flush()
            yield()
            yield()
            assertContentEquals(chunk, fast.chunks.receive())
        }
        assertContentEquals(writes[0], slow.chunks.receive())
        assertContentEquals(writes[1], slow.chunks.receive())
        assertFailsWith<SlowReaderException> { slow.chunks.receive() }
        body.writeFully(p[5])
        body.flush()
        assertContentEquals(p[5], fast.chunks.receive())
        fast.close()
    }

    @Test fun `a reader that asks for a larger buffer keeps a burst the default buffer would drop`() = runTest {
        val streams = SharedStreams(backgroundScope, FanoutConfig(readerChunks = 2)) { testScheduler.currentTime }
        val body = ByteChannel()
        val roomy = streams.join("source", chunks = 8) { consume -> consume(body) }
        val plain = streams.join("source") { error("unused") }
        val writes = listOf(p[0] + p[1] + p[2], p[3], p[4], p[5])
        for (chunk in writes) {
            body.writeFully(chunk)
            body.flush()
            yield()
            yield()
        }
        for (chunk in writes) assertContentEquals(chunk, roomy.chunks.receive())
        assertContentEquals(writes[0], plain.chunks.receive())
        assertContentEquals(writes[1], plain.chunks.receive())
        assertFailsWith<SlowReaderException> { plain.chunks.receive() }
        roomy.close()
    }

    @Test fun `dropping the last reader as slow stops the upstream after the linger`() = runTest {
        val streams =
            SharedStreams(backgroundScope, FanoutConfig(readerChunks = 2, lingerMillis = 2000)) {
                testScheduler.currentTime
            }
        val body = ByteChannel()
        val slow = streams.join("source") { consume -> consume(body) }
        for (chunk in listOf(p[0] + p[1] + p[2], p[3], p[4])) {
            body.writeFully(chunk)
            body.flush()
            yield()
            yield()
        }
        slow.chunks.receive()
        slow.chunks.receive()
        assertFailsWith<SlowReaderException> { slow.chunks.receive() }
        slow.close()
        assertEquals(1, streams.active())
        testScheduler.advanceTimeBy(2001)
        testScheduler.runCurrent()
        assertEquals(0, streams.active(), "the upstream is not left running for nobody")
    }

    @Test fun `only whole aligned packets are forwarded regardless of upstream chunking`() = runTest {
        val streams = SharedStreams(backgroundScope, FanoutConfig()) { testScheduler.currentTime }
        val body = ByteChannel()
        val reader = streams.join("source") { consume -> consume(body) }
        val stream = ByteArray(10) { 0x33 } + p[0] + p[1] + p[2] + p[3]
        for (piece in listOf(
            stream.copyOfRange(0, 100),
            stream.copyOfRange(100, 500),
            stream.copyOfRange(500, stream.size),
        )) {
            body.writeFully(piece)
            body.flush()
            yield()
            yield()
        }
        val received = mutableListOf<ByteArray>()
        while (received.sumOf { it.size } < 4 * 188) received += nextData(reader)
        assertTrue(received.all { it.size % 188 == 0 && it[0] == 0x47.toByte() })
        assertContentEquals(p[0] + p[1] + p[2] + p[3], received.reduce { acc, bytes -> acc + bytes })
        reader.close()
    }
}
