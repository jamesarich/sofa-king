package dev.jamesrich.sofaking.backend.playback

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemuxWatchTest {
    private var now = 0L
    private val stalls = StallWatch({ now })

    @Test fun `a stall is a reason with or without the floors`() {
        val watch = RemuxWatch(stalls, floors = false, blackLog = null, startedAt = 0)
        assertNull(watch.reason(0, 0))
        stalls.stalled("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\na.ts\n", java.net.URI("http://example.test/a"))
        now = 5_000
        stalls.stalled("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\na.ts\n", java.net.URI("http://example.test/a"))
        assertEquals(RemuxWatch.STALL, watch.reason(now, 0))
    }

    @Test fun `too low a rate for 30 seconds is a reason only with the floors on`() {
        val off = RemuxWatch(stalls, floors = false, blackLog = null, startedAt = 0)
        val on = RemuxWatch(stalls, floors = true, blackLog = null, startedAt = 0)
        // 64 kbit/s is 8,000 bytes a second; 7,000 a second is under it.
        for (second in 0..29) {
            assertNull(on.reason(second * 1_000L, second * 7_000L))
            assertNull(off.reason(second * 1_000L, second * 7_000L))
        }
        assertEquals(RemuxWatch.BITRATE, on.reason(30_000, 210_000))
        assertNull(off.reason(30_000, 210_000))
    }

    @Test fun `a steady rate is no reason`() {
        val watch = RemuxWatch(stalls, floors = true, blackLog = null, startedAt = 0)
        for (second in 0..60) assertNull(watch.reason(second * 1_000L, second * 500_000L))
    }

    @Test fun `black that lasts 30 seconds is a reason and its end clears it`() {
        val log = Files.createTempFile("sofa-black-test-", ".log")
        try {
            val watch = RemuxWatch(stalls, floors = true, blackLog = log, startedAt = 0)
            Files.writeString(log, "frame:0    pts:0       pts_time:0\nlavfi.black_start=0\n")
            assertNull(watch.reason(1_000, 1_000_000))
            assertNull(watch.reason(30_999, 31_000_000))
            assertEquals(RemuxWatch.BLACK, watch.reason(31_000, 31_000_000))
            Files.writeString(
                log,
                "frame:0    pts:0       pts_time:0\nlavfi.black_start=0\nframe:9    pts:9       pts_time:31\n" +
                    "lavfi.black_end=31\n",
            )
            assertNull(watch.reason(32_000, 32_000_000))
        } finally {
            Files.deleteIfExists(log)
        }
    }
}
