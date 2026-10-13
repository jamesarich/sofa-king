package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.upstream.TS_PACKET
import dev.jamesrich.sofaking.backend.upstream.TS_SYNC
import dev.jamesrich.sofaking.backend.upstream.sniffProtocol
import dev.jamesrich.sofaking.backend.upstream.tsSyncOffset
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Opens the upstream and hands its body to [consume]; returns when the body ends, throws when it fails. */
internal typealias UpstreamConnect = suspend (consume: suspend (ByteReadChannel) -> Unit) -> Unit

internal class UpstreamStatusException(val status: Int) : RuntimeException("Upstream status $status")

/** The upstream body carries [protocol], not the protocol the offering was imported with. */
internal class UpstreamProtocolException(val protocol: String) : RuntimeException("Upstream is $protocol")

internal class SlowReaderException : RuntimeException("Reader buffer overflow")

data class FanoutConfig(
    val keepaliveMillis: Long = 1000,
    val graceMillis: Long = 30_000,
    val lingerMillis: Long = 2000,
    val readerChunks: Int = 128,
    val maxBackoffMillis: Long = 8000,
)

/** The MPEG-TS null packet (PID 0x1FFF); players discard it, so it keeps a socket alive without touching the stream. */
internal val TS_NULL_PACKET: ByteArray = ByteArray(TS_PACKET) { 0xFF.toByte() }.also { it[0] = TS_SYNC; it[1] = 0x1F; it[2] = 0xFF.toByte(); it[3] = 0x10 }

/**
 * One upstream MPEG-TS connection fanned out to every session playing the same source.
 * Only whole packets are forwarded, so inserted null packets never split a packet.
 * A reader that cannot keep up is dropped; the upstream and the other readers continue.
 */
internal class TsFanout(
    val key: String,
    private val connect: UpstreamConnect,
    private val scope: CoroutineScope,
    private val config: FanoutConfig,
    private val now: () -> Long,
    private val onEnded: suspend (TsFanout) -> Unit,
) {
    inner class Reader internal constructor(internal val channel: Channel<ByteArray>) {
        val chunks: ReceiveChannel<ByteArray> get() = channel

        /** Completes once the first aligned packets arrived, or fails with the first connection's error. */
        suspend fun awaitStarted() = started.await()

        suspend fun close() = removeReader(this)
    }

    private val log = LoggerFactory.getLogger(TsFanout::class.java)
    private val mutex = Mutex()
    private val readers = LinkedHashSet<Reader>()
    private val started = CompletableDeferred<Unit>()
    private var ended = false
    private var linger: Job? = null
    private var producer: Job? = null
    private var keepalive: Job? = null

    @Volatile private var dataSinceTick = false

    @Volatile private var lastDataAt = now()

    @Volatile private var chunks = 0L

    @Volatile var reconnects = 0
        private set

    fun start() {
        producer = scope.launch(CoroutineName("ts-fanout")) { produce() }
        keepalive =
            scope.launch(CoroutineName("ts-keepalive")) {
                while (true) {
                    delay(config.keepaliveMillis)
                    if (dataSinceTick) dataSinceTick = false else broadcast(TS_NULL_PACKET)
                }
            }
    }

    /** Null once this stream has ended; the caller then creates a fresh one. */
    suspend fun addReader(): Reader? =
        mutex.withLock {
            if (ended) return@withLock null
            linger?.cancel()
            linger = null
            Reader(Channel(config.readerChunks)).also { readers += it }
        }

    private suspend fun removeReader(reader: Reader) {
        mutex.withLock {
            if (!readers.remove(reader)) return
            reader.channel.close()
            if (readers.isEmpty() && !ended) {
                linger =
                    scope.launch(CoroutineName("ts-linger")) {
                        delay(config.lingerMillis)
                        stop(null)
                    }
            }
        }
    }

    private suspend fun stop(cause: Throwable?) {
        val drained =
            mutex.withLock {
                if (ended) return
                ended = true
                readers.toList().also { readers.clear() }
            }
        drained.forEach { it.channel.close(cause) }
        if (!started.isCompleted) started.completeExceptionally(cause ?: IllegalStateException("Upstream ended before it started"))
        keepalive?.cancel()
        producer?.cancel()
        onEnded(this)
    }

    private suspend fun broadcast(packets: ByteArray) {
        val dropped =
            mutex.withLock {
                readers.filter { it.channel.trySend(packets).isFailure }.also { readers.removeAll(it.toSet()) }
            }
        dropped.forEach { it.channel.close(SlowReaderException()) }
        if (dropped.isNotEmpty()) log.warn("relay dropped slow readers offering={} count={}", key, dropped.size)
    }

    private suspend fun produce() {
        var cause: Throwable? = null
        try {
            try {
                pump()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!started.isCompleted) throw e
                log.info("relay upstream failed offering={} class={}", key, e.javaClass.simpleName)
            }
            var backoff = 500L
            while (readersPresent()) {
                val remaining = lastDataAt + config.graceMillis - now()
                if (remaining <= 0) {
                    log.warn("relay upstream gave up offering={} graceMillis={} reconnects={}", key, config.graceMillis, reconnects)
                    break
                }
                delay(backoff.coerceAtMost(remaining))
                backoff = (backoff * 2).coerceAtMost(config.maxBackoffMillis)
                reconnects++
                val before = chunks
                try {
                    pump()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.info("relay reconnect failed offering={} attempt={} class={} status={}", key, reconnects, e.javaClass.simpleName, (e as? UpstreamStatusException)?.status)
                }
                if (chunks != before) backoff = 500
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cause = e
            // A body that carries the other protocol is a detection the reader turns into 410 STREAM_CHANGED, not a failure.
            if (e is UpstreamProtocolException) log.info("relay upstream protocol offering={} protocol={}", key, e.protocol)
            else log.warn("relay upstream failed offering={} class={} status={}", key, e.javaClass.simpleName, (e as? UpstreamStatusException)?.status)
        } finally {
            withContext(NonCancellable) { stop(cause) }
        }
    }

    private suspend fun readersPresent() = mutex.withLock { readers.isNotEmpty() }

    private suspend fun pump() {
        connect { body ->
            if (started.isCompleted) log.info("relay reconnected offering={} attempt={}", key, reconnects)
            val buffer = ByteArray(32 * 1024)
            var carry = ByteArray(0)
            var synced = false
            var junk = 0
            while (true) {
                val read = body.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                val bytes = if (carry.isEmpty()) buffer.copyOf(read) else carry + buffer.copyOf(read)
                var offset = 0
                if (!synced) {
                    if (!started.isCompleted) sniffProtocol(bytes)?.let { if (it != "mpegts") throw UpstreamProtocolException(it) }
                    val sync = tsSyncOffset(bytes, 3)
                    if (sync == null) {
                        junk += read
                        if (junk > 64 * 1024) throw IllegalStateException("No MPEG-TS sync in upstream body")
                        carry = bytes.copyOfRange(maxOf(0, bytes.size - 3 * TS_PACKET), bytes.size)
                        continue
                    }
                    junk = 0
                    offset = sync
                    synced = true
                }
                var end = offset
                while (end + TS_PACKET <= bytes.size && bytes[end] == TS_SYNC) end += TS_PACKET
                if (end > offset) {
                    broadcast(bytes.copyOfRange(offset, end))
                    chunks++
                    dataSinceTick = true
                    lastDataAt = now()
                    if (!started.isCompleted) started.complete(Unit)
                }
                // A packet without a sync byte means alignment was lost; search again from it.
                if (end + TS_PACKET <= bytes.size) synced = false
                carry = bytes.copyOfRange(end, bytes.size)
            }
        }
    }
}

/** Registry of live fan-outs keyed by offering. */
internal class SharedStreams(
    private val scope: CoroutineScope,
    private val config: FanoutConfig = FanoutConfig(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private val streams = HashMap<String, TsFanout>()

    suspend fun join(
        key: String,
        connect: UpstreamConnect,
    ): TsFanout.Reader {
        while (true) {
            val stream =
                mutex.withLock {
                    streams[key] ?: TsFanout(key, connect, scope, config, now) { ended -> mutex.withLock { streams.remove(key, ended) } }
                        .also { streams[key] = it; it.start() }
                }
            stream.addReader()?.let { return it }
            mutex.withLock { streams.remove(key, stream) }
        }
    }

    suspend fun active(): Int = mutex.withLock { streams.size }
}
