package dev.jamesrich.sofaking.data

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.random.Random

/**
 * The PIN that locks Settings, as the store keeps it: a random salt and the SHA-256 of the salt and the digits,
 * joined by a colon, so the digits themselves are never on disk. It keeps a child or a guest out of Settings;
 * it is not a security boundary, and the server never sees it.
 */
object SettingsPin {
    const val LENGTH = 4

    /** True for exactly [LENGTH] digits. */
    fun isValid(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }

    /** The value the store keeps for [pin]; a fresh salt each time, so the same PIN never hashes the same twice. */
    fun hash(pin: String, salt: String = Random.nextBytes(SALT_BYTES).toByteString().hex()): String =
        "$salt$SEPARATOR" + (salt + pin).encodeUtf8().sha256().hex()

    /** True when [pin] is the one [stored] was made from; false for anything that is not a stored hash. */
    fun matches(pin: String, stored: String): Boolean {
        val salt = stored.substringBefore(SEPARATOR, missingDelimiterValue = "")
        return salt.isNotEmpty() && hash(pin, salt) == stored
    }

    private const val SALT_BYTES = 16
    private const val SEPARATOR = ':'
}
