package dev.jamesrich.sofaking.backend

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.test.*

class BackgroundTest {
    @Test fun `a failing background coroutine is logged by name and class, and its siblings keep running`() = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val scope = backgroundScope { lines += it }
        try {
            val sibling = scope.launch(CoroutineName("sibling")) { awaitCancellation() }
            scope.launch(CoroutineName("logo-sweep")) { throw IllegalStateException("https://example.test/?token=secret") }.join()
            assertEquals(listOf("background coroutine failed name=logo-sweep class=IllegalStateException"), lines.toList())
            assertTrue(sibling.isActive && scope.isActive)
            scope.launch { throw IOException() }.join()
            assertEquals("background coroutine failed name=unnamed class=IOException", lines.last())
        } finally { scope.cancel() }
    }

    @Test fun `a repeating loop logs a failed run and runs again`() = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val runs = AtomicInteger()
        val again = CompletableDeferred<Unit>()
        val job = launchEvery("session-expire", periodMillis = 1, report = { lines += it }) {
            if (runs.incrementAndGet() == 1) throw IllegalStateException("boom")
            again.complete(Unit)
        }
        try {
            withTimeout(10_000) { again.await() }
            assertEquals("session-expire failed class=IllegalStateException", lines.first())
        } finally { job.cancelAndJoin() }
    }

    @Test fun `runCatchingCancellable captures failures but lets cancellation through`() = runBlocking {
        assertTrue(runCatchingCancellable { throw IOException("down") }.isFailure)
        assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
        assertFailsWith<CancellationException> { runCatchingCancellable { throw CancellationException("stop") } }

        var carriedOn = false
        val started = CompletableDeferred<Unit>()
        val job = launch {
            runCatchingCancellable { started.complete(Unit); awaitCancellation() }
            carriedOn = true
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(carriedOn, "a cancelled caller does not continue past the call")
    }
}
