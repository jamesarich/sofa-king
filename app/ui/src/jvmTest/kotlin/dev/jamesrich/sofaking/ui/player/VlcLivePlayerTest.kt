package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The desktop player where libVLC is missing: looking for it never holds the thread that made the player. */
class VlcLivePlayerTest {
    private val stream = PlaybackStream("c1", "s1", "http://127.0.0.1:1/stream.ts", "video/mp2t", 1, PlaybackIntent.WATCH)

    @Test
    fun `a player answers at once while libVLC is still being looked for`() {
        val searching = CountDownLatch(1)
        val player = VlcLivePlayer(CaptionSync()) {
            searching.await()
            throw UnsatisfiedLinkError("no libvlc")
        }
        try {
            player.setStream(stream, null)
            player.prepare()
            player.volume = 0.5f
            player.playWhenReady = false
            assertFalse(player.isSeekable)
            assertEquals(0L, player.positionMs)
            assertFalse(player.hasTextTrack)
            assertEquals(StreamInfo(), player.streamInfo())
        } finally {
            searching.countDown()
            player.release()
        }
    }

    @Test
    fun `without libVLC every tune fails with VLC_NOT_INSTALLED`() = runBlocking {
        val player = VlcLivePlayer(CaptionSync()) { throw UnsatisfiedLinkError("no libvlc") }
        try {
            player.setStream(stream, null)
            repeat(2) {
                val error = async(start = CoroutineStart.UNDISPATCHED) { player.errors.first() }
                player.prepare()
                assertEquals(PlayerError(VLC_NOT_INSTALLED), withTimeout(5_000) { error.await() })
                assertEquals(PlaybackState.IDLE, player.status.value.state)
            }
        } finally {
            player.release()
        }
    }
}
