package dev.jamesrich.sofaking.backend.multiview

import dev.jamesrich.sofaking.backend.pollUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleRunTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A body that counts itself as running until canceled, and takes [release] to finish canceling. */
    private fun body(
        running: AtomicInteger,
        started: CompletableDeferred<Unit>? = null,
        canceling: CompletableDeferred<Unit>? = null,
        release: CompletableDeferred<Unit>? = null,
    ): suspend () -> Unit = {
        running.incrementAndGet()
        started?.complete(Unit)
        try {
            CompletableDeferred<Unit>().await()
        } finally {
            withContext(NonCancellable) {
                canceling?.complete(Unit)
                release?.await()
                running.decrementAndGet()
            }
        }
    }

    @Test fun `a replace queued behind a stop starts nothing`() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val run = SingleRun(scope, Dispatchers.Default)
            val running = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val canceling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            assertTrue(run.replace { body(running, started, canceling, release) })
            started.await()

            val stopping = async { run.stop {} }
            canceling.await()
            val replacing = async { run.replace { body(running) } }
            release.complete(Unit)
            stopping.await()

            assertFalse(replacing.await())
            assertEquals(0, running.get())
        }
        scope.cancel()
    }

    @Test fun `a stop queued behind a replace ends the run the replace started`() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val run = SingleRun(scope, Dispatchers.Default)
            val running = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val canceling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            run.replace { body(running, started, canceling, release) }
            started.await()

            val replacing = async { run.replace { body(running) } }
            canceling.await()
            val stopping = async { run.stop {} }
            release.complete(Unit)
            replacing.await()
            stopping.await()

            assertEquals(0, running.get())
        }
        scope.cancel()
    }

    @Test fun `two replaces at once leave one run`() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val run = SingleRun(scope, Dispatchers.Default)
            val running = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            run.replace { body(running, started) }
            started.await()

            val both = List(2) { async { run.replace { body(running) } } }
            both.forEach { assertTrue(it.await()) }
            pollUntil(TIMEOUT_MS) { running.get() >= 1 }
            run.stop {}

            assertEquals(0, running.get())
        }
        scope.cancel()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
