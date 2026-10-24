package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import dev.jamesrich.sofaking.backend.catalog.httpUri
import dev.jamesrich.sofaking.backend.setup.ServiceCatalog
import io.ktor.http.Parameters
import kotlinx.html.A
import kotlinx.html.a
import kotlinx.html.address
import kotlinx.html.form
import kotlinx.html.hidden
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.map
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.s

/** The form's fields, less the session's, as hidden inputs of the confirmation carry them. */
internal fun Parameters.setupFields(): Map<String, List<String>> = names().filter {
    it != "csrf" && it != "confirm"
}.associateWith { getAll(it).orEmpty() }

/** The host of [url], or a stand-in, for a page that must not show a path or query. */
internal fun hostOnly(url: String?): String =
    url?.trim()?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "a URL"

/** The services form's checkbox that builds the starter lineup in the same save. */
internal const val STARTER_FIELD = "starter"

/** Whether the services form asks for the starter lineup too. */
internal fun Parameters.wantsStarter(): Boolean = this[STARTER_FIELD] == "on"

/** The form field each checked service's box sends its name under. */
internal const val SERVICE_FIELD = "svc"

/** The form field that holds one of [service]'s options. */
internal fun optionField(service: String, key: String) = "opt.$service.$key"

/**
 * [document] with the services form applied: a checked service keeps its providers (the first takes the form's
 * options), an unchecked one loses them, and a checked service with none gets one named after it. Playlists and guides
 * stay as they are. Fails with every option that does not fit.
 */
internal fun applyServicesForm(document: SourceDocument, form: Parameters): Result<SourceDocument> {
    val checked = form.getAll(SERVICE_FIELD).orEmpty().toSet()
    val errors = ArrayList<String>()
    val providers = ArrayList<FeedConfig>()
    val taken = document.providers.mapTo(HashSet()) { it.id }
    for (info in ServiceCatalog.all) {
        val existing = document.providers.filter { it.native && it.service == info.service }
        if (info.service !in checked) continue
        val options = LinkedHashMap(existing.firstOrNull()?.options.orEmpty())
        for (option in info.options) {
            val value = form[optionField(info.service, option.key)]?.trim().orEmpty()
            when {
                value.isEmpty() && option.required -> errors += "${info.label}: ${option.label} is required"

                value.isEmpty() -> options.remove(option.key)

                option.pattern != null && !option.pattern.matches(value) ->
                    errors +=
                        "${info.label}: ${option.label} does not look right (${option.hint})"

                else -> options[option.key] = value
            }
        }
        if (existing.isEmpty()) {
            val id = generateSequence(info.service) { "$it-2" }.first { it !in taken }
            providers += FeedConfig(id, type = "native", service = info.service, options = options)
        } else {
            providers += existing.first().copy(options = options)
            providers += existing.drop(1)
        }
    }
    if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString("\n")))
    // Keep the file's order for the providers that stay; new ones go at the end.
    val kept = document.providers.mapNotNull { original ->
        providers.firstOrNull { it.id == original.id }
            ?: original.takeIf { !it.native }
    }
    val added = providers.filter { p -> document.providers.none { it.id == p.id } }
    return Result.success(document.copy(providers = kept + added))
}

/** [document] with an M3U playlist added from the add form: `id`, `url` and an optional `guide`. */
internal fun applyPlaylistForm(document: SourceDocument, form: Parameters): Result<SourceDocument> {
    val id = form["id"]?.trim().orEmpty()
    val url = form["url"]?.trim().orEmpty()
    val guide = form["guide"]?.trim()?.takeIf { it.isNotEmpty() }
    val errors = ArrayList<String>()
    if (!id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) {
        errors += "The ID is letters, digits, - and _, up to 64 characters"
    } else if (document.providers.any { it.id == id }) {
        errors += "A provider named $id already exists"
    }
    if (runCatching { httpUri(url) }.isFailure) errors += "The playlist URL must be an http or https address"
    if (guide != null && runCatching { httpUri(guide) }.isFailure) {
        errors += "The guide URL must be an http or https address"
    }
    if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString("\n")))
    return Result.success(
        document.copy(
            providers =
            document.providers + FeedConfig(id, playlistUrl = url, guideUrl = guide),
        ),
    )
}

/** What the services form changes against [document], as the confirmation lists it. */
internal fun servicesChanges(document: SourceDocument, next: SourceDocument): List<String> {
    fun services(d: SourceDocument) = d.providers.filter { it.native }.map { it.service!! }.toSet()
    val before = services(document)
    val after = services(next)
    fun label(service: String) = ServiceCatalog.byService(service)?.label ?: service
    val lines = ArrayList<String>()
    (after - before).sorted().takeIf {
        it.isNotEmpty()
    }?.let { lines += "Turns on ${it.joinToString { s -> label(s) }}" }
    (before - after).sorted().takeIf { it.isNotEmpty() }?.let {
        lines +=
            "Turns off ${it.joinToString { s -> label(s) }}; their channels leave the lineup's stations as sources"
    }
    val changedOptions = (before intersect after).filter { service ->
        document.providers.first { it.native && it.service == service }.options !=
            next.providers.first { it.native && it.service == service }.options
    }
    if (changedOptions.isNotEmpty()) lines += "Changes the options of ${changedOptions.joinToString { label(it) }}"
    return lines
}
