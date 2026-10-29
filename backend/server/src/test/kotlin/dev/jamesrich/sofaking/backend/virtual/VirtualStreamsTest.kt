package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.api.VirtualChannel
import dev.jamesrich.sofaking.backend.api.VirtualSlot
import dev.jamesrich.sofaking.backend.api.VirtualSource
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.library.LibraryCatalog
import dev.jamesrich.sofaking.backend.pollFor
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveVirtualChannel
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VirtualStreamsTest {
    private val dir: Path = Files.createTempDirectory("sofa-virtual-streams-")
    private val clock = MutableClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun cleanup() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    /** An FFmpeg that says it is up, runs until killed and ignores SIGTERM, so a stop spends its grace period on it. */
    private fun stubborn(): String {
        val script = dir.resolve("ffmpeg")
        Files.writeString(script, "#!/bin/sh\ntrap '' TERM\necho up\nwhile :; do sleep 1; done\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.toString()
    }

    private suspend fun build(mayStart: suspend () -> Boolean = { true }): Pair<VirtualStreams, BackendStore> {
        val store = openStore(dir.resolve("data"), clock)
        for (id in listOf("c1", "c2")) {
            val slot = VirtualSlot(VirtualSource(SourceKind.LIBRARY, "none"))
            val channel = VirtualChannel(id = id, name = id, slots = listOf(slot))
            store.saveVirtualChannel(channel, clock.instant(), clock.instant())
        }
        val channels = VirtualChannelService(store, LibraryCatalog(store, clock) { 0L }, clock)
        return VirtualStreams(stubborn(), dir.resolve("virtual"), channels, clock, scope, mayStart = mayStart) to store
    }

    @Test fun `a stream started while the same channel stops keeps its files`() = runBlocking {
        withTimeout(TEST_MS) {
            val (streams, store) = build()
            val first = launch(Dispatchers.Default) { streams.playlist("c1") }
            val log = dir.resolve("virtual/c1/0/ffmpeg.log")
            pollUntil(WAIT_MS) { Files.exists(log) && Files.readString(log).startsWith("up") }
            first.cancel()

            clock.now = clock.now.plus(VirtualStreams.IDLE).plusSeconds(1)
            val stopping = launch(Dispatchers.Default) { streams.expire() }
            pollUntil(WAIT_MS) { streams.running == 0 }
            val second = launch(Dispatchers.Default) { streams.playlist("c1") }
            stopping.join()

            pollFor(WAIT_MS) { Files.exists(log).takeIf { it && streams.running == 1 } }
            second.cancel()
            streams.close()
            store.close()
        }
    }

    @Test fun `simultaneous starts stay within the shared cap`() = runBlocking {
        withTimeout(TEST_MS) {
            lateinit var streams: VirtualStreams
            val (built, store) = build(mayStart = {
                delay(CHECK_MS)
                streams.running < 1
            })
            streams = built
            val refused = AtomicInteger()
            val calls = listOf("c1", "c2").map { id ->
                async(Dispatchers.Default) {
                    try {
                        streams.playlist(id)
                    } catch (e: ApiFailure) {
                        if (e.code == Wire.ErrorCode.CAPACITY_EXHAUSTED) refused.incrementAndGet()
                    }
                }
            }
            pollUntil(WAIT_MS) { streams.running + refused.get() == calls.size }
            assertEquals(1, streams.running)
            assertEquals(1, refused.get())
            calls.forEach { it.cancel() }
            streams.close()
            store.close()
        }
    }

    private companion object {
        const val TEST_MS = 60_000L
        const val WAIT_MS = 15_000L
        const val CHECK_MS = 100L
    }
}
