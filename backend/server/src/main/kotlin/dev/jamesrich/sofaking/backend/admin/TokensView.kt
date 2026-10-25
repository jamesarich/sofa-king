package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.ApiTokenInfo
import dev.jamesrich.sofaking.backend.api.CreatedApiToken
import dev.jamesrich.sofaking.backend.api.TokenScope
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** The issued tokens the Settings page lists, and the one just made, whose secret it shows this once. */
internal class TokensView(val tokens: List<ApiTokenInfo>, val created: CreatedApiToken? = null)

private val SCOPE_LABELS = mapOf(
    TokenScope.READ to "Read: status, sessions, guides, lineup and libraries, for a dashboard",
    TokenScope.HOOK to "Hook: the inbound hooks, for Sonarr, Radarr and Bazarr",
)

private fun scopeName(scope: TokenScope) = when (scope) {
    TokenScope.API -> "Device"
    TokenScope.READ -> "Read"
    TokenScope.HOOK -> "Hook"
}

internal fun FlowContent.tokensPanel(chrome: Chrome, view: TokensView) = panel("Access tokens", id = "tokens") {
    p("mt-0") {
        +(
            "Tokens for a dashboard or a hook, so neither holds the API token. Each paired device has one too; " +
                "forgetting the device on the "
            )
        a("/admin/viewers#devices") { +"Devices" }
        +" page revokes it."
    }
    view.created?.let { created ->
        div("notice warn") {
            attributes["role"] = "status"
            id = "created-token"
            +"Copy the ${created.token.label} token now; the server keeps only a hash and cannot show it again: "
            code { +created.secret }
        }
    }
    if (view.tokens.isNotEmpty()) tokensTable(chrome, view.tokens)
    postForm("/admin/settings/tokens/create", chrome.session, classes = "runtime") {
        div("field") {
            label {
                htmlFor = "token-label"
                +"Label"
            }
            textInput(name = "label") {
                id = "token-label"
                required = true
                maxLength = "80"
            }
            span("hint") { +"What holds it, such as Homepage or Sonarr" }
        }
        div("field") {
            label {
                htmlFor = "token-scope"
                +"Opens"
            }
            select {
                id = "token-scope"
                name = "scope"
                for ((scope, text) in SCOPE_LABELS) {
                    option {
                        value = scope.name.lowercase()
                        +text
                    }
                }
            }
        }
        div("actions") {
            button(type = ButtonType.submit, classes = "primary") { +"Make a token" }
        }
    }
}

private fun FlowContent.tokensTable(chrome: Chrome, tokens: List<ApiTokenInfo>) = div("scroll") {
    table {
        caption("sr") { +"Access tokens" }
        thead {
            tr {
                col("Label")
                col("Kind")
                col("Made")
                col("Last used")
                col("Action")
            }
        }
        tbody {
            for (token in tokens) {
                tr(flashClass(chrome, token.id)) {
                    id = "token-${token.id}"
                    th {
                        attributes["scope"] = "row"
                        +token.label
                        token.clientId?.let { span("muted small mono") { +" $it" } }
                    }
                    td { +scopeName(token.scope) }
                    td("nowrap") { ago(token.createdAt, chrome.now) }
                    td("nowrap") { ago(token.lastUsedAt, chrome.now) }
                    td {
                        postForm("/admin/settings/tokens/${token.id}/revoke", chrome.session, keepScroll = true) {
                            button(type = ButtonType.submit, classes = "small") {
                                attributes["aria-label"] = "Revoke ${token.label}"
                                +"Revoke"
                            }
                        }
                    }
                }
            }
        }
    }
}
