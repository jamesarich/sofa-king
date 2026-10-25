package dev.jamesrich.sofaking.backend.pairing

import dev.jamesrich.sofaking.backend.api.ApiTokenInfo
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.StoredApiToken
import dev.jamesrich.sofaking.backend.store.activeApiTokens
import dev.jamesrich.sofaking.backend.store.markApiTokenUsed
import dev.jamesrich.sofaking.backend.store.revokeApiToken
import dev.jamesrich.sofaking.backend.store.revokeClientApiTokens
import dev.jamesrich.sofaking.backend.store.saveApiToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

/** How stale a token's stored last use may get before a request writes it again. */
private val USE_RESOLUTION: Duration = Duration.ofMinutes(1)
private const val SECRET_BYTES = 32
private const val ID_BYTES = 6

/** Every secret the server issues starts with this, so a leaked one is recognizable in a scan. */
internal const val ISSUED_TOKEN_PREFIX = "sk_"

/**
 * The tokens the server issues on top of `SOFA_API_TOKEN` and `SOFA_ADMIN_TOKEN`: one `api` token per paired device,
 * which forgetting the device revokes, and the `read` and `hook` tokens the admin makes. Only a hash of each secret is
 * stored; the active ones are kept in memory, so the guard answers a request without a database read.
 */
class IssuedTokens(
    private val store: BackendStore,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Mutex()

    @Volatile private var byHash: Map<String, StoredApiToken>? = null

    /** The active token whose secret is [secret], recording the use; null for any other string. */
    suspend fun resolve(secret: String): StoredApiToken? {
        if (!secret.startsWith(ISSUED_TOKEN_PREFIX)) return null
        val token = active()[hash(secret)] ?: return null
        val now = clock.instant()
        if (token.lastUsedAt == null || token.lastUsedAt.plus(USE_RESOLUTION).isBefore(now)) {
            lock.withLock {
                byHash = byHash?.plus(token.secretHash to token.copy(lastUsedAt = now))
                store.markApiTokenUsed(token.id, now)
            }
        }
        return token
    }

    /** Every active token, oldest first, without secrets. */
    suspend fun all(): List<ApiTokenInfo> = active().values.sortedBy { it.createdAt }.map { it.info() }

    /** The devices that hold a token of their own. */
    suspend fun pairedClients(): Set<String> = active().values.mapNotNullTo(mutableSetOf()) { it.clientId }

    /** Makes a `read` or `hook` token called [label]; the secret is in the answer only. */
    suspend fun create(label: String, scope: TokenScope): Pair<ApiTokenInfo, String> {
        require(scope != TokenScope.API) { "API tokens come from pairing" }
        return lock.withLock { issue(label, scope, null) }
    }

    /**
     * The `api` token for a device that just claimed a pairing code, revoking the ones [clientId] was paired with
     * before. A claim without a client id gets a token no device row will ever revoke; old apps send one.
     */
    suspend fun pair(clientId: String?, label: String): String = lock.withLock {
        if (clientId != null) store.revokeClientApiTokens(clientId, clock.instant())
        issue(label.ifEmpty { "Paired device" }, TokenScope.API, clientId).second
    }

    /** Revokes the token [id]; false when no active token has it. */
    suspend fun revoke(id: String): Boolean = lock.withLock {
        store.revokeApiToken(id, clock.instant()).also { reload() }
    }

    /** Revokes the tokens [clientId] was paired with, and says how many there were. */
    suspend fun revokeClient(clientId: String): Int = lock.withLock {
        store.revokeClientApiTokens(clientId, clock.instant()).also { reload() }
    }

    private fun hash(secret: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))

    private suspend fun issue(label: String, scope: TokenScope, clientId: String?): Pair<ApiTokenInfo, String> {
        val secret = ISSUED_TOKEN_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SECRET_BYTES).also(random::nextBytes))
        val id = HexFormat.of().formatHex(ByteArray(ID_BYTES).also(random::nextBytes))
        val token = StoredApiToken(id, label, hash(secret), scope, clientId, clock.instant(), null)
        store.saveApiToken(token)
        reload()
        return token.info() to secret
    }

    private suspend fun active(): Map<String, StoredApiToken> = byHash ?: lock.withLock { byHash ?: reload() }

    private suspend fun reload(): Map<String, StoredApiToken> =
        store.activeApiTokens().associateBy { it.secretHash }.also { byHash = it }
}

private fun StoredApiToken.info() =
    ApiTokenInfo(id, label, scope, clientId, createdAt.toString(), lastUsedAt?.toString())
