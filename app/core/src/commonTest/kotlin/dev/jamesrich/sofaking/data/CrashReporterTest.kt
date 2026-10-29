package dev.jamesrich.sofaking.data

import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CrashReporterTest {
    private val settings = DataStoreSettingsStore(MemoryDataStore(emptyPreferences()))
    private val context = CrashContext("0.30.0", "android-tv", "Android 14", "Chromecast")

    /** A store in memory, oldest key first. */
    private class MemoryCrashes : CrashStore {
        val files = mutableMapOf<String, String>()

        override fun save(key: String, json: String) {
            files[key] = json
        }

        override fun pending() = files.entries.sortedBy { it.key }.map { StoredCrash(it.key, it.value) }

        override fun remove(key: String) {
            files.remove(key)
        }
    }

    private class FakeServer(scope: TestScope, var status: Int = 204) {
        val http = FakeHttp(scope) { request ->
            if (request.method == "POST" && request.path == "/api/v1/diagnostics/crashes") {
                if (status == 204) respond("", HttpStatusCode.NoContent) else apiError(status, "ERROR")
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private fun record(store: CrashStore, vararg messages: String, keep: Int = 3) {
        var tick = 1L
        var n = 0
        val clock = { Instant.fromEpochMilliseconds(tick++ * 1000) }
        val recorder = CrashRecorder(store, context, keep, clock) { "id-${n++}" }
        messages.forEach { recorder.record("java.lang.IllegalStateException", it, "x\n\tat a.B.c(B.kt:1)") }
    }

    private fun TestScope.start(
        store: CrashStore,
        server: FakeServer?,
        retry: kotlin.time.Duration = 60.seconds,
    ): MutableStateFlow<SofaKingApi?> {
        val servers = MutableStateFlow(server?.http?.api())
        val reporter = CrashReporter(store, settings, retry, UnconfinedTestDispatcher(testScheduler))
        backgroundScope.launch { reporter.run(servers) }
        runCurrent()
        return servers
    }

    @Test
    fun `a kept crash is sent to the server and deleted once accepted`() = runTest {
        val crashes = MemoryCrashes()
        record(crashes, "boom")
        val server = FakeServer(this)
        start(crashes, server)
        val sent = server.http.requests.single()
        assertEquals("Bearer secret", sent.headers["Authorization"])
        assertTrue(sent.body.contains(""""exception":"java.lang.IllegalStateException""""), sent.body)
        assertTrue(sent.body.contains(""""device":"Chromecast""""), sent.body)
        assertTrue(crashes.files.isEmpty())
    }

    @Test
    fun `the report carries no server address and no token`() = runTest {
        val crashes = MemoryCrashes()
        record(crashes, "GET http://sofa.test/api/v1/channels failed with Bearer abcdefghijklmnopqrstuvwxyz0123456789")
        val server = FakeServer(this)
        start(crashes, server)
        val body = server.http.requests.single().body
        assertFalse(body.contains("sofa.test"), body)
        assertFalse(body.contains("secret"), body)
        assertFalse(body.contains("abcdefghijklmnop"), body)
        assertFalse(body.contains("://"), body)
    }

    @Test
    fun `a crash stays kept until a server is configured`() = runTest {
        val crashes = MemoryCrashes()
        record(crashes, "boom")
        val servers = start(crashes, null)
        assertEquals(1, crashes.files.size)
        val server = FakeServer(this)
        servers.value = server.http.api()
        runCurrent()
        assertEquals(1, server.http.requests.size)
        assertTrue(crashes.files.isEmpty())
    }

    @Test
    fun `nothing is sent and the kept crashes are deleted while the setting is off`() = runTest {
        settings.setSendCrashReports(false)
        val crashes = MemoryCrashes()
        record(crashes, "boom")
        val server = FakeServer(this)
        start(crashes, server)
        assertTrue(server.http.requests.isEmpty())
        assertTrue(crashes.files.isEmpty())
    }

    @Test
    fun `turning the setting on again sends only crashes recorded after`() = runTest {
        settings.setSendCrashReports(false)
        val crashes = MemoryCrashes()
        record(crashes, "old")
        val server = FakeServer(this)
        start(crashes, server)
        record(crashes, "new")
        settings.setSendCrashReports(true)
        runCurrent()
        assertEquals(1, server.http.requests.size)
        assertTrue(server.http.requests.single().body.contains("new"))
    }

    @Test
    fun `sending is on until the setting is turned off`() = runTest {
        assertTrue(settings.settings.first().sendCrashReports)
        settings.setSendCrashReports(false)
        assertFalse(settings.settings.first().sendCrashReports)
    }

    @Test
    fun `a server that does not take reports keeps them and is asked once`() = runTest {
        val crashes = MemoryCrashes()
        record(crashes, "boom")
        val server = FakeServer(this, status = 404)
        start(crashes, server)
        advanceTimeBy(300.seconds)
        runCurrent()
        assertEquals(1, server.http.requests.size)
        assertEquals(1, crashes.files.size)
    }

    @Test
    fun `a report the server calls malformed is dropped`() = runTest {
        val crashes = MemoryCrashes()
        record(crashes, "boom")
        val server = FakeServer(this, status = 400)
        start(crashes, server)
        assertTrue(crashes.files.isEmpty())
    }

    @Test
    fun `a busy server is asked again later and the crash is deleted when it accepts`() = runTest {
        val crashes = MemoryCrashes()
        record(crashes, "boom")
        val server = FakeServer(this, status = 503)
        start(crashes, server, retry = 30.seconds)
        assertEquals(1, server.http.requests.size)
        assertEquals(1, crashes.files.size)
        server.status = 204
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(2, server.http.requests.size)
        assertTrue(crashes.files.isEmpty())
    }

    @Test
    fun `an unreadable kept file is dropped and the rest are sent`() = runTest {
        val crashes = MemoryCrashes()
        crashes.save("0001-bad", "not json")
        record(crashes, "boom")
        val server = FakeServer(this)
        start(crashes, server)
        assertEquals(1, server.http.requests.size)
        assertTrue(crashes.files.isEmpty())
    }

    @Test
    fun `only the newest three crashes are kept`() = runTest {
        val crashes = MemoryCrashes()
        record(crashes, "one", "two", "three", "four", "five")
        assertEquals(3, crashes.files.size)
        assertEquals(listOf("id-2", "id-3", "id-4"), crashes.files.keys.sorted().map { it.substringAfter("-") })
    }

    @Test
    fun `a store that fails does not stop the crash from being handled`() {
        val failing = object : CrashStore {
            override fun save(key: String, json: String) = error("disk full")

            override fun pending() = error("disk full")

            override fun remove(key: String) = error("disk full")
        }
        CrashRecorder(failing, context).record("X", "m", "")
    }

    @Test
    fun `the outcome of an answer decides whether the crash is kept`() {
        assertEquals(CrashOutcome.DROP, crashOutcome(400))
        assertEquals(CrashOutcome.DROP, crashOutcome(413))
        assertEquals(CrashOutcome.UNSUPPORTED, crashOutcome(401))
        assertEquals(CrashOutcome.UNSUPPORTED, crashOutcome(404))
        assertEquals(CrashOutcome.RETRY, crashOutcome(429))
        assertEquals(CrashOutcome.RETRY, crashOutcome(500))
    }
}
