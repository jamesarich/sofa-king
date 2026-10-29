package dev.jamesrich.sofaking.backend

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

private val SHARED_RANDOM = SecureRandom()

/** The SHA-256 of [value]'s UTF-8 bytes, lowercase hex. */
internal fun hash(value: String): String = hash(value.toByteArray())

/** The SHA-256 of [bytes], lowercase hex. */
internal fun hash(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** [bytes] random bytes as lowercase hex. */
internal fun randomHex(bytes: Int, random: SecureRandom = SHARED_RANDOM): String =
    HexFormat.of().formatHex(ByteArray(bytes).also(random::nextBytes))

/** [bytes] random bytes as unpadded URL-safe Base64. */
internal fun randomUrlToken(bytes: Int, random: SecureRandom = SHARED_RANDOM): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
