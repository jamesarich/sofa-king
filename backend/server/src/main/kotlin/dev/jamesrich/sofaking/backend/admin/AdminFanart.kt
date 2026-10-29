package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.SavedSecret
import dev.jamesrich.sofaking.backend.library.LibraryKind
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.p

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
        if (SavedSecret.FANART in view.settable) secretForm(chrome, view, SavedSecret.FANART, view.fanart)
        p("muted small") { +"Images by fanart.tv's users, under its terms of use." }
    }
}
