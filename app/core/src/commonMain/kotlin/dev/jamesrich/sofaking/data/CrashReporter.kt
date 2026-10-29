package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What to do with a kept crash once the server has answered, or failed to. */
internal enum class CrashOutcome {
    /** The server stored it: forget it. */
    SENT,

    /** The server will never take this report as it is (too large, malformed): forget it. */
    DROP,

    /** The server does not take reports (an older one, or a token that may not): keep it and stop for now. */
    UNSUPPORTED,

    /** The server is busy or unreachable: keep it and ask again later. */
    RETRY,
}

/** The outcome of an attempt that ended in [status], an HTTP status. */
internal fun crashOutcome(status: Int): CrashOutcome = when (status) {
    HttpStatusCode.BadRequest.value, HttpStatusCode.PayloadTooLarge.value -> CrashOutcome.DROP

    HttpStatusCode.Unauthorized.value, HttpStatusCode.Forbidden.value, HttpStatusCode.NotFound.value,
    HttpStatusCode.MethodNotAllowed.value,
    -> CrashOutcome.UNSUPPORTED

    else -> CrashOutcome.RETRY
}

/**
 * Sends the crashes [CrashRecorder] kept to the server Settings names, once, while [AppSettings.sendCrashReports] is
 * on, and forgets each once the server stored it. With the setting off nothing is sent and the crashes kept are
 * deleted. The reports go to that server only.
 */
class CrashReporter(
    private val store: CrashStore,
    private val settings: SettingsStore,
    private val retryAfter: Duration = 1.minutes,
    private val io: CoroutineDispatcher = ioDispatcher,
) {
    /** Follows [servers] for as long as the caller collects: each new server, or the setting, restarts the pass. */
    suspend fun run(servers: Flow<SofaKingApi?>) {
        val enabled = settings.settings.map { it.sendCrashReports }.distinctUntilChanged()
        combine(servers, enabled) { api, on -> api to on }.collectLatest { (api, on) ->
            when {
                !on -> forgetAll()
                api != null -> send(api)
            }
        }
    }

    private suspend fun forgetAll() = withContext(io) { store.pending().forEach { store.remove(it.key) } }

    private suspend fun send(api: SofaKingApi) {
        while (!sendKept(api)) delay(retryAfter)
    }

    /** One pass over the crashes kept; false when the server was busy and a later pass should ask again. */
    private suspend fun sendKept(api: SofaKingApi): Boolean {
        var busy = false
        for (crash in withContext(io) { store.pending() }) {
            val outcome = attempt(api, crash)
            if (outcome == CrashOutcome.SENT || outcome == CrashOutcome.DROP) {
                withContext(io) { store.remove(crash.key) }
            } else {
                busy = outcome == CrashOutcome.RETRY
                break
            }
        }
        return !busy
    }

    private suspend fun attempt(api: SofaKingApi, crash: StoredCrash): CrashOutcome {
        val report = runCatchingCancellable { crashJson.decodeFromString<CrashReportDto>(crash.json) }.getOrNull()
            ?: return CrashOutcome.DROP
        return try {
            api.sendCrashReport(report)
            CrashOutcome.SENT
        } catch (e: SofaKingException) {
            log.w { "crash report refused (${e.code})" }
            crashOutcome(e.status)
        } catch (e: IOException) {
            log.w { "crash report not sent (${e.message})" }
            CrashOutcome.RETRY
        }
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
    }
}
