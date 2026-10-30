package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.NS_PER_MS
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.upstream.TS_PACKET
import dev.jamesrich.sofaking.backend.upstream.TS_SYNC
import dev.jamesrich.sofaking.backend.upstream.sniffProtocol
import dev.jamesrich.sofaking.backend.upstream.tsSyncOffset
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
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
internal val TS_NULL_PACKET: ByteArray = ByteArray(TS_PACKET) { 0xFF.toByte() }.also {
    it[0] = TS_SYNC
    it[1] = 0x1F
    it[2] =
        0xFF.toByte()
    it[3] = 0x10
}

/** How much of the upstream body one read takes. */
private const val READ_SIZE = 32 * 1024

/** The first wait before a reconnect; each failed one doubles it. */
private const val FIRST_BACKOFF_MS = 500L

/** How far into an upstream body a TS sync byte must turn up. */
private const val SYNC_SEARCH_LIMIT = 64 * 1024

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

    /**
     * Starts reading the source; the first [addReader] must follow within the linger, or the stream
     * stops as if its last reader left.
     */
    fun start() {
        // Only the registry, under its lock, has this stream yet, so nothing races the linger set here.
        linger = scope.launch(CoroutineName("ts-linger")) {
            delay(config.lingerMillis)
            stop(null)
        }
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
    suspend fun addReader(chunks: Int = config.readerChunks): Reader? = mutex.withLock {
        if (ended) return@withLock null
        linger?.cancel()
        linger = null
        Reader(Channel(chunks)).also { readers += it }
    }

    private suspend fun removeReader(reader: Reader) {
        mutex.withLock {
            if (!readers.remove(reader)) return
            reader.channel.close()
            lingerIfIdle()
        }
    }

    /**
     * Under [mutex]: once the last reader is gone, by leaving or by falling behind, the stream stops after the linger.
     */
    private fun lingerIfIdle() {
        if (readers.isNotEmpty() || ended || linger != null) return
        linger =
            scope.launch(CoroutineName("ts-linger")) {
                delay(config.lingerMillis)
                stop(null)
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
        if (!started.isCompleted) {
            started.completeExceptionally(cause ?: IllegalStateException("Upstream ended before it started"))
        }
        keepalive?.cancel()
        producer?.cancel()
        onEnded(this)
    }

    private suspend fun broadcast(packets: ByteArray) {
        // The usual broadcast drops nobody and allocates nothing; the list exists only once a reader fell behind.
        var dropped: MutableList<Reader>? = null
        mutex.withLock {
            readers.removeIf { reader ->
                reader.channel.trySend(packets).isFailure.also { failed ->
                    if (failed) (dropped ?: ArrayList<Reader>().also { dropped = it }) += reader
                }
            }
            if (dropped != null) lingerIfIdle()
        }
        val slow = dropped ?: return
        slow.forEach { it.channel.close(SlowReaderException()) }
        log.warn("relay dropped slow readers offering={} count={}", key, slow.size)
    }

    private suspend fun produce() {
        var cause: Throwable? = null
        try {
            runCatchingCancellable { relay() }.onFailure { e ->
                cause = e
                // A body that carries the other protocol is a detection the reader turns into 410
                // STREAM_CHANGED, not a failure.
                if (e is UpstreamProtocolException) {
                    log.info("relay upstream protocol offering={} protocol={}", key, e.protocol)
                } else {
                    log.warn(
                        "relay upstream failed offering={} class={} status={}",
                        key,
                        e.javaClass.simpleName,
                        (e as? UpstreamStatusException)?.status,
                    )
                }
            }
        } finally {
            withContext(NonCancellable) { stop(cause) }
        }
    }

    /** Reads the upstream, and reconnects with backoff while readers remain and the grace period lasts. */
    private suspend fun relay() {
        runCatchingCancellable {
            pump()
        }.onFailure { e ->
            if (!started.isCompleted) throw e
            log.info("relay upstream failed offering={} class={}", key, e.javaClass.simpleName)
        }
        var backoff = FIRST_BACKOFF_MS
        while (readersPresent()) {
            val remaining = lastDataAt + config.graceMillis - now()
            if (remaining <= 0) {
                log.warn(
                    "relay upstream gave up offering={} graceMillis={} reconnects={}",
                    key,
                    config.graceMillis,
                    reconnects,
                )
                break
            }
            delay(backoff.coerceAtMost(remaining))
            backoff = (backoff * 2).coerceAtMost(config.maxBackoffMillis)
            reconnects++
            val before = chunks
            runCatchingCancellable {
                pump()
            }.onFailure { e ->
                log.info(
                    "relay reconnect failed offering={} attempt={} class={} status={}",
                    key,
                    reconnects,
                    e.javaClass.simpleName,
                    (e as? UpstreamStatusException)?.status,
                )
            }
            if (chunks != before) backoff = FIRST_BACKOFF_MS
        }
    }

    private suspend fun readersPresent() = mutex.withLock { readers.isNotEmpty() }

    private suspend fun pump() {
        connect { body ->
            if (started.isCompleted) log.info("relay reconnected offering={} attempt={}", key, reconnects)
            val packets = PacketBuffer()
            var read = packets.read(body)
            while (read >= 0) {
                if (read > 0) packets.take(read, sniff = !started.isCompleted)?.let { send(it) }
                read = packets.read(body)
            }
        }
    }

    private suspend fun send(packets: ByteArray) {
        broadcast(packets)
        chunks++
        dataSinceTick = true
        lastDataAt = now()
        if (!started.isCompleted) started.complete(Unit)
    }
}

/**
 * One upstream body cut into whole MPEG-TS packets, in one working buffer: the unsent tail stays at its front and
 * each read lands after it.
 */
private class PacketBuffer {
    private var work = ByteArray(READ_SIZE + 3 * TS_PACKET)
    private var carried = 0
    private var synced = false
    private var junk = 0

    /** Reads what [body] has after the tail: how many bytes, or -1 at its end. */
    suspend fun read(body: ByteReadChannel): Int {
        if (work.size - carried < READ_SIZE) work = work.copyOf(carried + READ_SIZE)
        return body.readAvailable(work, carried, READ_SIZE)
    }

    /**
     * The whole packets the [read] bytes complete, a copy since readers hold on to what they are sent; null when
     * there are none. Until the first packet, [sniff] checks the body is MPEG-TS at all.
     */
    fun take(read: Int, sniff: Boolean): ByteArray? {
        val size = carried + read
        val offset = if (synced) 0 else sync(read, size, sniff) ?: return null
        var end = offset
        while (end + TS_PACKET <= size && work[end] == TS_SYNC) end += TS_PACKET
        val packets = if (end > offset) work.copyOfRange(offset, end) else null
        // A packet without a sync byte means alignment was lost; search again from it.
        if (end + TS_PACKET <= size) synced = false
        work.copyInto(work, 0, end, size)
        carried = size - end
        return packets
    }

    /** Where the first packet starts in the [size] bytes held; null while none does, keeping the last few packets. */
    private fun sync(read: Int, size: Int, sniff: Boolean): Int? {
        if (sniff) sniffProtocol(work, size)?.let { if (it != "mpegts") throw UpstreamProtocolException(it) }
        val sync = tsSyncOffset(work, 3, to = size)
        if (sync == null) {
            junk += read
            check(junk <= SYNC_SEARCH_LIMIT) { "No MPEG-TS sync in upstream body" }
            val keep = minOf(size, 3 * TS_PACKET)
            work.copyInto(work, 0, size - keep, size)
            carried = keep
        } else {
            junk = 0
            synced = true
        }
        return sync
    }
}

/** Registry of live fan-outs keyed by offering. */
internal class SharedStreams(
    private val scope: CoroutineScope,
    private val config: FanoutConfig = FanoutConfig(),
    private val now: () -> Long = { System.nanoTime() / NS_PER_MS },
) {
    private val mutex = Mutex()
    private val streams = HashMap<String, TsFanout>()

    suspend fun join(key: String, connect: UpstreamConnect): TsFanout.Reader = join(key, null, connect)

    /** As above, with a reader that buffers [chunks] chunks, the config's default when null. */
    suspend fun join(key: String, chunks: Int?, connect: UpstreamConnect): TsFanout.Reader {
        while (true) {
            val stream =
                mutex.withLock {
                    streams[key]
                        ?: TsFanout(key, connect, scope, config, now) { ended ->
                            mutex.withLock { streams.remove(key, ended) }
                        }
                            .also {
                                streams[key] = it
                                it.start()
                            }
                }
            (if (chunks == null) stream.addReader() else stream.addReader(chunks))?.let { return it }
            mutex.withLock { streams.remove(key, stream) }
        }
    }

    suspend fun active(): Int = mutex.withLock { streams.size }
}
