package dev.jamesrich.sofaking.backend.pairing

import dev.jamesrich.sofaking.backend.admin.sameSecret
import dev.jamesrich.sofaking.contract.Wire
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A code the operator can show a viewer, valid until [expiresAt]. */
class IssuedCode(val code: String, val expiresAt: Instant)

/**
 * Short-lived pairing codes: the admin portal shows one, a client sends it to `POST /api/v1/pair` and receives the
 * API token. One code is live at a time, for [lifetime]; a new one replaces it. A code is spent by the claim that
 * matches it, and voided by [maxAttempts] claims that do not, so guessing six digits never pays. Nothing is stored;
 * a restart simply forgets the code.
 */
class PairingService(private val clock: Clock, private val lifetime: Duration = Duration.ofMinutes(10), private val maxAttempts: Int = 5, private val random: SecureRandom = SecureRandom()) {
    private var live: IssuedCode? = null
    private var attempts = 0

    /** Issues a new code, replacing any live one. */
    @Synchronized
    fun issue(): IssuedCode {
        val digits = (1..Wire.PAIRING_CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }
        attempts = 0
        return IssuedCode(digits, clock.instant().plus(lifetime)).also { live = it }
    }

    /** The code the portal shows, while it is live. */
    @Synchronized
    fun current(): IssuedCode? = live?.takeIf { it.expiresAt.isAfter(clock.instant()) }

    /** Whether [code] is the live code; a match spends it, [maxAttempts] misses void it. */
    @Synchronized
    fun claim(code: String): Boolean {
        val issued = current() ?: run { live = null; return false }
        if (sameSecret(code.trim(), issued.code)) { live = null; return true }
        if (++attempts >= maxAttempts) live = null
        return false
    }

    /** Drops the live code, if any. */
    @Synchronized
    fun cancel() { live = null }
}
