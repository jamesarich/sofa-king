package dev.jamesrich.sofaking.backend.catalog

import java.net.URI
import java.net.URISyntaxException
import java.util.HexFormat

/**
 * The HTTP(S) URL a logo reference means, or null when it means none. Surrounding whitespace is dropped, a
 * relative or scheme-relative reference is resolved against [base], characters a URL may not carry (spaces,
 * quotes, non-ASCII) are percent-encoded without touching escapes already present, and anything that is not an
 * absolute http(s) URL with a host and no user info afterwards is null.
 */
fun normalizeLogoUrl(raw: String?, base: String? = null): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.length > 2048) return null
    val resolved = try {
        val reference = URI(percentEncode(trimmed))
        when {
            reference.scheme != null -> reference
            base != null -> URI(percentEncode(base.trim())).resolve(reference)
            else -> return null
        }
    } catch (_: URISyntaxException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (resolved.scheme?.lowercase() !in setOf("http", "https") || resolved.host == null || resolved.userInfo != null) {
        return null
    }
    return resolved.toString().takeIf { it.length <= 2048 }
}

private const val UNENCODED = "-._~:/?#[]@!$&'()*+,;="

/** [value] with every character a URL may not carry percent-encoded as UTF-8; escapes already present are kept. */
internal fun percentEncode(value: String): String {
    val out = StringBuilder(value.length + 16)
    var index = 0
    while (index < value.length) {
        val c = value[index]
        when {
            c == '%' && index + 2 < value.length && isHex(value[index + 1]) && isHex(value[index + 2]) -> {
                out.append(value, index, index + 3)
                index += 3
                continue
            }

            c.code in 0x21..0x7E && (c.isLetterOrDigit() || c in UNENCODED) -> out.append(c)

            else -> {
                val point = value.codePointAt(index)
                for (byte in String(
                    Character.toChars(point),
                ).toByteArray(Charsets.UTF_8)) {
                    HEX.toHexDigits(out.append('%'), byte)
                }
                index += Character.charCount(point)
                continue
            }
        }
        index++
    }
    return out.toString()
}

private val HEX: HexFormat = HexFormat.of().withUpperCase()

private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
