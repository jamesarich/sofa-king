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

/** The fanart.tv panel, shown once a Music library exists or a key is set. */
internal fun FlowContent.fanartPanel(chrome: Chrome, view: LibraryView) {
    if (!view.fanart && view.libraries.none { it.kind == LibraryKind.MUSIC }) return
    panel("Music artwork", id = "fanart") {
        p("mt-0") {
            +(
                if (view.fanart) {
                    "Music libraries take artists' pictures and backdrops, and covers for albums with none, from " +
                        "fanart.tv after each scan. A picture or cover in the folder wins over fanart.tv."
                } else {
                    "Artists' pictures come from Wikipedia where it has a free one. Add a fanart.tv API key for " +
                        "artists' pictures and backdrops and more album covers: it is free from "
                }
                )
            if (!view.fanart) {
                a("https://fanart.tv/get-an-api-key/") { +"fanart.tv" }
                +"."
            }
        }
        if (view.fanartSettable) fanartKeyForm(chrome, view)
        p("muted small") { +"Images by fanart.tv's users, under its terms of use." }
    }
}

private fun FlowContent.fanartKeyForm(chrome: Chrome, view: LibraryView) {
    notice(view.fanartError?.let { "Not saved: $it" })
    postForm("/admin/library/fanart", chrome.session, classes = "stack mt-12") {
        div("field") {
            label {
                htmlFor = "fanart-key"
                +(if (view.fanart) "Replace the fanart.tv key" else "fanart.tv API key")
            }
            passwordInput(name = "key") {
                id = "fanart-key"
                required = true
                attributes["autocomplete"] = "off"
                attributes["spellcheck"] = "false"
            }
        }
        div {
            button(type = ButtonType.submit, classes = if (view.fanart) null else "primary") { +"Check and save" }
        }
    }
    p("muted small") {
        +(
            "The server asks fanart.tv whether it takes the key, keeps it in secrets.json beside the generated " +
                "tokens, and restarts."
            )
    }
}
