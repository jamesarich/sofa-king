package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import dev.jamesrich.sofaking.backend.providers.httpOrNull
import dev.jamesrich.sofaking.backend.providers.instantOrNull
import dev.jamesrich.sofaking.backend.providers.long
import dev.jamesrich.sofaking.backend.providers.truthy
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import dev.jamesrich.sofaking.backend.providers.usRating as ratingOf

internal val FIELD_TYPES = setOf("epochSeconds", "iso")
internal val FILTER_OPS = setOf("eq", "neq", "truthy", "falsy", "http", "nonEmpty", "startsWith")

/** The value [spec] reads from [item]: text, or an [Instant] or [ProgramRating] when it says `as` or `usRating`. */
internal fun FieldSpec.read(item: JsonElement?): Any? {
    var text: String? = when {
        value != null -> value
        path != null -> item.textAt(path)
        firstOf != null -> firstOf.firstNotNullOfOrNull { item.textAt(it) }
        firstHttp != null -> firstHttp.firstNotNullOfOrNull { httpOrNull(item.textAt(it)) }
        else -> null
    }
    if (text == null && fallback != null) text = item.textAt(fallback)
    if (before != null) text = text?.substringBefore(before)
    if (trim) text = text?.trim()?.takeIf { it.isNotEmpty() }
    if (http) text = httpOrNull(text)
    if (sentenceCase) text = text?.lowercase()?.replaceFirstChar(Char::uppercase)
    if (dropIf != null && text.equals(dropIf, ignoreCase = true)) text = null
    if (unlessEquals != null && text == item.textAt(unlessEquals)) text = null
    return when {
        text == null -> null
        asType == "epochSeconds" -> text.toLongOrNull()?.let(Instant::ofEpochSecond)
        asType == "iso" -> instantOrNull(text)
        usRating -> ratingOf(text)
        else -> text
    }
}

internal fun Field?.text(item: JsonElement?): String? = this?.spec?.read(item) as? String

internal fun Field?.instant(item: JsonElement?): Instant? = this?.spec?.read(item) as? Instant

/**
 * Whether [item] passes the filter; a missing value reads as [ManifestFilter.default] when there is one. With
 * [ManifestFilter.anyOf] the value is the first of those paths that has one.
 */
internal fun ManifestFilter.passes(item: JsonElement?): Boolean {
    val text = (anyOf?.firstNotNullOfOrNull { item.textAt(it) } ?: item.textAt(path)) ?: default
    return when (op) {
        "eq" -> text == value
        "neq" -> text != value
        "startsWith" -> value != null && text?.startsWith(value, ignoreCase = true) == true
        "truthy" -> item.at(path).isSet()
        "falsy" -> !item.at(path).isSet()
        "http" -> httpOrNull(text) != null
        "nonEmpty" -> text != null
        else -> false
    }
}

/** JSON `true`, the text "true" or a number other than zero. */
private fun JsonElement?.isSet(): Boolean = truthy || (long ?: 0L) != 0L
