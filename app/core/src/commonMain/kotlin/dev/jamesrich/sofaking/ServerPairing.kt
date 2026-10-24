package dev.jamesrich.sofaking

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.DeviceSettings
import dev.jamesrich.sofaking.data.PairingClaimDto
import dev.jamesrich.sofaking.data.ServerAddress
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.pair
import dev.jamesrich.sofaking.data.runCatchingCancellable
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val log = Logger.withTag("SofaKing")

/** Pairing with a server by the code it shows, as the screens ask for it. */
interface PairingActions {
    /** Where pairing stands: claiming a code, and how the last attempt went. */
    val pairing: StateFlow<PairingState>

    /**
     * Claims the pairing [code] at [url] and, when the server accepts it, saves the address and the token it hands
     * out; nothing is saved when it fails. The claim runs in the ViewModel's scope, so leaving the screen or a
     * configuration change does not cut it off between the server's answer and the save, and [pairing] carries the
     * outcome. A second call while one is claimed is ignored.
     */
    fun pair(url: String, code: String)

    /** [pair] for a host that awaits the answer, as the Apple TV app does: null once paired, else why it failed. */
    suspend fun pairAndWait(url: String, code: String): PairingFailure?

    /** The screen that asked has acted on [PairingState.outcome]; the failure stays for Settings to show. */
    fun pairingHandled()

    /** Settings closed the server block that showed the last failure, or saved another server; it shows no more. */
    fun pairingDismissed()
}

/** [PairingActions] over [connection], saving the server in [store]; [client] is how this device names itself. */
internal class ServerPairing(
    private val store: DeviceSettings,
    private val connection: ServerConnection,
    private val client: ClientInfo,
    private val scope: CoroutineScope,
) : PairingActions {
    private val state = MutableStateFlow(PairingState())

    override val pairing: StateFlow<PairingState> = state.asStateFlow()

    override fun pair(url: String, code: String) {
        if (state.value.busy) return
        state.value = PairingState(busy = true)
        scope.launch { claim(url, code) }
    }

    override suspend fun pairAndWait(url: String, code: String): PairingFailure? =
        scope.async { claim(url, code) }.await()

    override fun pairingHandled() {
        state.update { it.copy(outcome = null) }
    }

    override fun pairingDismissed() {
        state.update { it.copy(failure = null) }
    }

    private suspend fun claim(url: String, code: String): PairingFailure? {
        state.value = PairingState(busy = true)
        val failure = claimCode(url, code)
        state.value =
            PairingState(
                failure = failure,
                outcome = if (failure == null) {
                    PairingOutcome.PAIRED
                } else {
                    PairingOutcome.FAILED
                },
            )
        return failure
    }

    private suspend fun claimCode(url: String, code: String): PairingFailure? {
        val server = ServerAddress.normalizeUrl(url) ?: return PairingFailure.INVALID_CODE
        val digits = ServerAddress.normalizeCode(code) ?: return PairingFailure.INVALID_CODE
        return runCatchingCancellable {
            val clientId = store.ensureClientId()
            val result = connection.newApi(server, "").use {
                it.pair(PairingClaimDto(digits, clientId, client.deviceName, client.platform))
            }
            store.setServer(server, result.apiToken)
            null
        }.getOrElse { e ->
            if (e is SofaKingException) {
                log.w { "pairing refused: ${e.code}" }
                when {
                    e.code == Wire.ErrorCode.PAIRING_INVALID -> PairingFailure.INVALID_CODE

                    e.statusCode == HttpStatusCode.NotFound || e.statusCode == HttpStatusCode.Unauthorized ->
                        PairingFailure.OLDER_SERVER

                    else -> PairingFailure.UNREACHABLE
                }
            } else {
                log.w { "pairing failed: ${e.message}" }
                PairingFailure.UNREACHABLE
            }
        }
    }
}
