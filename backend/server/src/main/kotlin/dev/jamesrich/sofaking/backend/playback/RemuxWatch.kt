package dev.jamesrich.sofaking.backend.playback

import java.nio.file.Files
import java.nio.file.Path

/** How long a remuxed source may send less than [BITRATE_FLOOR_KBPS], or show black, before it is replaced. */
internal const val FLOOR_MILLIS = 30_000L

/** The rate a remuxed source must keep up over [FLOOR_MILLIS]; a slate or a frozen picture sends less. */
internal const val BITRATE_FLOOR_KBPS = 64L

/**
 * Why one remux run should move to another source, read about once a second: its source [stalls], and with the
 * quality floors on, a rate under [BITRATE_FLOOR_KBPS] or a black picture for [FLOOR_MILLIS]. FFmpeg writes where
 * black starts and ends to [blackLog] as `lavfi.black_start` and `lavfi.black_end` lines.
 */
internal class RemuxWatch(
    private val stalls: StallWatch,
    private val floors: Boolean,
    private val blackLog: Path?,
    private val startedAt: Long,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var blackLines = 0
    private var blackSince: Long? = null

    /** The reason to move on at [now], with [sent] bytes sent so far; null while the source is fine. */
    fun reason(now: Long, sent: Long): String? {
        if (stalls.tripped) return STALL
        if (!floors) return null
        samples.addLast(now to sent)
        while (samples.size > 1 && now - samples[1].first >= FLOOR_MILLIS) samples.removeFirst()
        val (from, before) = samples.first()
        if (now - startedAt >= FLOOR_MILLIS && now - from >= FLOOR_MILLIS &&
            (sent - before) * BITS_PER_BYTE < BITRATE_FLOOR_KBPS * (now - from)
        ) {
            return BITRATE
        }
        readBlack(now)
        return blackSince?.takeIf { now - it >= FLOOR_MILLIS }?.let { BLACK }
    }

    /** Reads the lines FFmpeg added to [blackLog]: a start keeps the time it was first read, an end clears it. */
    private fun readBlack(now: Long) {
        val log = blackLog ?: return
        val lines = runCatching { Files.readAllLines(log) }.getOrNull() ?: return
        for (line in lines.drop(blackLines)) {
            when {
                line.startsWith("lavfi.black_start") -> if (blackSince == null) blackSince = now
                line.startsWith("lavfi.black_end") -> blackSince = null
            }
        }
        blackLines = lines.size
    }

    companion object {
        const val STALL = "stall"
        const val BITRATE = "bitrate"
        const val BLACK = "black"

        /** Bytes to kilobits over milliseconds: 8 bits per byte, and kbit/s is bit/ms. */
        private const val BITS_PER_BYTE = 8L
    }
}
