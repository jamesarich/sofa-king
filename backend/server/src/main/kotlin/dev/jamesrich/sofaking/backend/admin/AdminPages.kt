package dev.jamesrich.sofaking.backend.admin

import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FormMethod
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dir
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hidden
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.main
import kotlinx.html.meter
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.q
import kotlinx.html.s
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.table

/** Where the docs an attention item links live: this repository's backend docs, by file and heading. */
internal const val DOCS_BASE = "https://github.com/jamesarich/sofa-king/blob/main/backend/docs/"

internal fun signInPage(enabled: Boolean, error: String? = null, theme: String? = null): String =
    layout("Sign in", null, null, theme) {
        div("panel signin") {
            h1("mt-0") { +"Sign in" }
            if (!enabled) {
                p {
                    +(
                        "The portal needs a token to sign in with. Set SOFA_ADMIN_TOKEN (or SOFA_API_TOKEN) and " +
                            "restart the server."
                        )
                }
                return@div
            }
            notice(error)
            form(action = "/admin/login", method = FormMethod.post, classes = "stack") {
                div("field") {
                    label {
                        htmlFor = "token"
                        +"Admin token"
                    }
                    passwordInput(name = "token") {
                        id = "token"
                        required = true
                        autoFocus = true
                        attributes["autocomplete"] =
                            "current-password"
                    }
                    span("hint") {
                        +(
                            "SOFA_ADMIN_TOKEN, or SOFA_API_TOKEN when no admin token is set. A token the server " +
                                "generated is in secrets.json in the data directory: "
                            )
                        code { +"docker compose exec sofa-king-backend cat /config/secrets.json" }
                    }
                }
                button(type = ButtonType.submit, classes = "primary") { +"Sign in" }
            }
        }
    }

/** What a share is multiplied by to read as a percentage. */
internal const val PERCENT = 100

internal fun stateChip(row: StationRow): Pair<String, Tone> {
    if (row.entry.enabled) return "On" to Tone.OK
    val reason = row.station?.disabledReason ?: row.entry.autoDisabledReason ?: "hidden"
    val tone = when (reason) {
        "unverified" -> Tone.WARN
        "no-source", "unavailable", "missing" -> Tone.BAD
        else -> Tone.OFF
    }
    val label = when (reason) {
        "policy", "hidden" -> "Hidden"
        "unverified" -> "Unverified"
        "no-source" -> "No source"
        "unavailable" -> "Unavailable"
        "missing" -> "Missing"
        else -> reason
    }
    return label to tone
}

internal fun guideChip(status: String): Pair<String, Tone> = when (status) {
    "available" -> "Guide" to Tone.OK
    "stale" -> "Guide ended" to Tone.WARN
    "empty" -> "Guide empty" to Tone.WARN
    else -> "No guide" to Tone.OFF
}

/** The filters as hidden fields, only those set, so an action brings the page back as it was. */
internal fun FORM.filterFields(filter: LineupFilter) {
    for ((name, value) in listOf(
        "q" to filter.q,
        "group" to filter.group,
        "state" to filter.state,
        "guide" to filter.guide,
    )) {
        if (value.isNotEmpty()) hiddenInput(name = name) { this.value = value }
    }
    if (filter.required) hiddenInput(name = "required") { value = "true" }
    if (filter.override) hiddenInput(name = "override") { value = "true" }
    if (filter.changed) hiddenInput(name = "changed") { value = "true" }
    if (filter.sort.isNotEmpty()) hiddenInput(name = "sort") { value = filter.sort }
    if (filter.dir.isNotEmpty()) hiddenInput(name = "dir") { value = filter.dir }
    if (filter.page > 1) hiddenInput(name = "page") { value = filter.page.toString() }
}

internal fun MAIN.noPolicy(what: String) {
    div("notice warn") {
        +(
            "$what needs a lineup policy. Add policy.json to the data directory, or set SOFA_POLICY, and restart; " +
                "without one the lineup is edited entry by entry through the admin API."
            )
    }
}

/** The meter on the lineup card fills in steps of this many percent. */
internal const val METER_STEP = 5

/** The class of a table row that [chrome]'s notice is about: an error row or a flash; null for any other row. */
internal fun flashClass(chrome: Chrome, target: String): String? =
    chrome.flash?.takeIf { it.target == target }?.let { if (it.tone == Tone.BAD) "err-row" else "flash" }
