package dev.jamesrich.sofaking.ui.guide

/** The query the recognizer heard: its first non-blank result when it finished, else null (dismissed or heard nothing). */
internal fun voiceQuery(ok: Boolean, results: List<String>?): String? =
    if (!ok) null else results?.firstOrNull { it.isNotBlank() }?.trim()
