package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.SavedSecret
import dev.jamesrich.sofaking.backend.library.LibraryKind
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.p

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
        if (SavedSecret.LISTENBRAINZ in view.settable) {
            secretForm(chrome, view, SavedSecret.LISTENBRAINZ, view.listenBrainz)
        }
    }
}
