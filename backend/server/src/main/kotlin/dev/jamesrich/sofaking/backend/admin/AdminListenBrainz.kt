package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.library.LibraryKind
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.passwordInput

/** The ListenBrainz panel, shown once a Music library exists or a token is set. */
internal fun FlowContent.listenBrainzPanel(chrome: Chrome, view: LibraryView) {
    if (!view.listenBrainz && view.libraries.none { it.kind == LibraryKind.MUSIC }) return
    panel("Music listening statistics", id = "listenbrainz") {
        p("mt-0") {
            +(
                if (view.listenBrainz) {
                    "Artists' pages list the songs ListenBrainz's listeners play most, and the artists they play " +
                        "alongside them, after each scan."
                } else {
                    "Artists' pages list the artists ListenBrainz's listeners play alongside them. Add a " +
                        "ListenBrainz user token for their most played songs too: it is free from your "
                }
                )
            if (!view.listenBrainz) {
                a("https://listenbrainz.org/settings/") { +"ListenBrainz settings" }
                +"."
            }
        }
        if (view.listenBrainzSettable) listenBrainzTokenForm(chrome, view)
    }
}

private fun FlowContent.listenBrainzTokenForm(chrome: Chrome, view: LibraryView) {
    notice(view.listenBrainzError?.let { "Not saved: $it" })
    postForm("/admin/library/listenbrainz", chrome.session, classes = "stack mt-12") {
        div("field") {
            label {
                htmlFor = "listenbrainz-token"
                +(if (view.listenBrainz) "Replace the ListenBrainz token" else "ListenBrainz user token")
            }
            passwordInput(name = "token") {
                id = "listenbrainz-token"
                required = true
                attributes["autocomplete"] = "off"
                attributes["spellcheck"] = "false"
            }
        }
        div {
            button(type = ButtonType.submit, classes = if (view.listenBrainz) null else "primary") {
                +"Check and save"
            }
        }
    }
    p("muted small") {
        +(
            "The server asks ListenBrainz whether it takes the token, keeps it in secrets.json beside the " +
                "generated tokens, and restarts."
            )
    }
}
