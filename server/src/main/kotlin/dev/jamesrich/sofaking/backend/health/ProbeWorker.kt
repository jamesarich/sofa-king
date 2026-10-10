package dev.jamesrich.sofaking.backend.health

import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.BackendStore
import java.nio.file.Files
import java.time.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** One process at a time. Persistent health timestamps determine recovery after restart. */
class ProbeWorker(private val feeds: FeedService, private val store: BackendStore, private val clock: Clock, private val ffmpeg: String, private val onHealthChanged: suspend (String) -> Unit = {}, private val qualifier: (suspend (Offering, Map<String, String>) -> FailureClass?)? = null) {
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            try { step() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Keep worker alive; never print media URLs. */ }
            delay(5000)
        }
    }
    suspend fun step(): Boolean {
        val now = clock.instant()
        val states = store.allHealth()
        val attempts = store.probeAttempts()
        val offering = feeds.offerings().sortedBy { attempts[it.id] ?: Instant.MIN }.filter { feeds.config(it.providerId).probe }.firstOrNull { offering ->
            val state = states[offering.id] ?: return@firstOrNull false
            !state.manuallyDisabled && state.status != HealthStatus.ARCHIVED && due(state, now) &&
                (attempts[offering.id]?.let { now >= it.plusSeconds(300) } != false)
        } ?: return false
        store.markProbeAttempt(offering.id, now)
        val headers = feeds.config(offering.providerId).headers
        val failure = if (qualifier == null) qualify(offering, headers) else qualifier.invoke(offering, headers)
        val at = clock.instant()
        store.recordObservation(offering.id, Observation(UUID.randomUUID().toString(), at, failure == null, failure), at)
        onHealthChanged(offering.id)
        return true
    }
    internal fun classify(error: String): FailureClass = when {
        Regex("(?i)(HTTP error |server returned )(401|403)\\b").containsMatchIn(error) -> FailureClass.AUTH
        Regex("(?i)(HTTP error |server returned )429\\b").containsMatchIn(error) -> FailureClass.RATE_LIMIT
        Regex("(?i)HTTP error 5[0-9]{2}|server returned 5[0-9]{2}|connection refused|failed to resolve|network is unreachable|connection timed out|connection reset|broken pipe|TLS handshake").containsMatchIn(error) -> FailureClass.PROVIDER
        Regex("(?i)decoder .*not found|unknown decoder|permission denied|no space left|cannot allocate memory|resource temporarily unavailable|protocol not found").containsMatchIn(error) -> FailureClass.LOCAL
        else -> FailureClass.OFFERING
    }
    private fun due(state: HealthState, now: Instant): Boolean {
        if (state.nextCheckAt != null) return now >= state.nextCheckAt
        val last = state.lastObservationAt ?: return true
        return now >= last.plus(if (state.status == HealthStatus.HEALTHY) Duration.ofHours(6) else Duration.ofMinutes(5))
    }
    private suspend fun qualify(offering: Offering, headers: Map<String, String>): FailureClass? = withContext(Dispatchers.IO) {
        val output = Files.createTempFile("sofa-probe-", ".progress")
        val errors = Files.createTempFile("sofa-probe-", ".errors")
        var process: Process? = null
        try {
            val command = mutableListOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-rw_timeout", "15000000")
            if (headers.isNotEmpty()) command += listOf("-headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
            command += listOf("-i", offering.url, "-t", "30", "-map", "0:v:0", "-an", "-progress", "pipe:1", "-f", "null", "-")
            process = ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(errors.toFile()).start()
            val owned = process
            val exited = runInterruptible { owned.waitFor(50, TimeUnit.SECONDS) }
            if (!exited) FailureClass.PROVIDER
            else {
                val progress = Files.readString(output)
                val frames = Regex("(?m)^frame=(\\d+)$").findAll(progress).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val micros = Regex("(?m)^out_time_us=(\\d+)$").findAll(progress).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0
                if (owned.exitValue() == 0 && frames >= 30 && micros >= 29_000_000) null else classify(Files.newInputStream(errors).use { it.readNBytes(8192).toString(Charsets.UTF_8) })
            }
        } catch (e: CancellationException) { throw e }
        catch (_: java.io.IOException) { FailureClass.LOCAL }
        finally {
            process?.let { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) } }
            Files.deleteIfExists(output)
            Files.deleteIfExists(errors)
        }
    }
}
