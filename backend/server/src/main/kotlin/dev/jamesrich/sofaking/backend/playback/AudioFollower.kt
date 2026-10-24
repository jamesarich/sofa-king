package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger(AudioFollower::class.java)

/**
 * Reads the audio of one [AdClock]'s segments, in the order they were fetched, and hands [heard] (the clock's
 * [AdClock.heard]) each one's levels and the Chromaprint points that are new with it, placed by [placement]. A
 * playlist's audio is one stream from segment to segment, so its points fall on one grid wherever a segment starts,
 * as if the whole stream were fingerprinted at once: each read starts at the next point due and keeps the audio
 * after the last point it gave for the next segment. A segment that is not the one after the last read of its
 * playlist starts the stream again. Segments are queued, a few at most; one that does not fit is dropped, and the
 * one after it starts the stream again.
 */
internal class AudioFollower(
    private val audio: SegmentAudio,
    private val scope: CoroutineScope,
    private val placement: (String) -> Placement?,
    private val heard: (url: String, levels: FloatArray, points: IntArray?, firstPointMs: Long) -> Unit,
) {
    constructor(
        audio: SegmentAudio,
        ads: AdClock,
        scope: CoroutineScope,
    ) : this(audio, scope, ads::placement, ads::heard)

    private val queue = Channel<Pair<String, ByteArray>>(QUEUE)
    private val running = AtomicBoolean(false)

    /** By playlist; only the reading coroutine touches them. */
    private val streams = object : LinkedHashMap<String, Stream>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stream>) = size > STREAMS
    }

    private class Stream(var sequence: Long) {
        /** Samples read so far, and the sample the next point is due at. */
        var samples = 0L
        var next = 0L

        /** The audio from [next] on. */
        var pending = ByteArray(0)

        /** The first sample of each recent segment, with where the segment starts on its timeline. */
        val segments = ArrayDeque<Pair<Long, Long>>()

        fun timeOf(sample: Long): Long {
            val (first, startMs) = segments.lastOrNull { it.first <= sample } ?: segments.first()
            return startMs + (sample - first) * 1_000 / AUDIO_RATE
        }
    }

    fun offer(url: String, segment: ByteArray) {
        if (queue.trySend(url to segment).isFailure) return
        if (running.compareAndSet(false, true)) scope.launch { drain() }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val (url, segment) = withTimeoutOrNull(IDLE_MS) { queue.receive() } ?: break
                runCatchingCancellable {
                    read(url, segment)
                }.onFailure { e ->
                    log.debug("segment audio failed: {}", e.toString())
                }
            }
        } finally {
            running.set(false)
        }
        // A segment offered as the reader stopped would wait for the next; start again for it.
        if (!queue.isEmpty && running.compareAndSet(false, true)) scope.launch { drain() }
    }

    private suspend fun read(url: String, segment: ByteArray) {
        val place = placement(url) ?: return
        val pcm = audio.pcm(segment) ?: return
        val previous = streams[place.playlist]
        val stream =
            previous?.takeIf { place.sequence == it.sequence + 1 }
                ?: Stream(place.sequence).also { streams[place.playlist] = it }
        stream.sequence = place.sequence
        stream.segments.addLast(stream.samples to place.startMs)
        while (stream.segments.size > SEGMENTS) stream.segments.removeFirst()
        stream.samples += pcm.size / 2
        stream.pending += pcm
        var points: IntArray? = null
        var firstPointMs = 0L
        if (stream.pending.size / 2 >= CHROMAPRINT_CONTEXT) {
            val read = audio.points(stream.pending)
            if (read == null) {
                stream.pending = ByteArray(0)
                stream.next = stream.samples
            } else {
                points = read
                firstPointMs = stream.timeOf(stream.next)
                val used = read.size * CHROMAPRINT_HOP
                stream.next += used
                stream.pending = stream.pending.copyOfRange(minOf(stream.pending.size, used * 2), stream.pending.size)
            }
        }
        heard(url, loudness(pcm), points, firstPointMs)
    }

    private companion object {
        const val QUEUE = 8
        const val STREAMS = 4
        const val SEGMENTS = 16
        const val IDLE_MS = 60_000L
    }
}
