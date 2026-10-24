package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.contract.Recording
import io.ktor.http.HttpStatusCode
import kotlinx.html.A
import kotlinx.html.S
import kotlinx.html.a
import kotlinx.html.form
import kotlinx.html.label
import kotlinx.html.s
import kotlinx.html.title

/** A recording's name as a list shows it: the show, then its season and episode or episode title. */
internal fun recordingName(recording: Recording): String {
    val numbering = if (recording.season != null && recording.episode != null) {
        "S%02dE%02d".format(recording.season, recording.episode)
    } else {
        null
    }
    return listOfNotNull(recording.title, numbering, recording.episodeTitle).joinToString(" · ")
}

/**
 * The whole number a recordings form sent as [name], [default] when it sent none, or a 400 naming
 * the field as the form labels it.
 */
internal fun formInt(form: io.ktor.http.Parameters, name: String, label: String, default: Int? = null): Int {
    val text = form[name]?.trim()
    if (text.isNullOrEmpty() && default != null) return default
    return text?.toIntOrNull()
        ?: throw dev.jamesrich.sofaking.backend.catalog.ApiFailure(
            HttpStatusCode.BadRequest,
            "INVALID_REQUEST",
            "$label must be a whole number",
        )
}
