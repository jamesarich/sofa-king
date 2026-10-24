package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.dvr.Retention
import dev.jamesrich.sofaking.contract.RecordingList
import dev.jamesrich.sofaking.contract.RecordingRule
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.s
import kotlinx.html.time

/**
 * [channels] are the ones with a guide, which a rule may name; [allChannels] are every enabled one, which a manual
 * recording may name. [zone] is the time zone the manual recording form's times are read in.
 */
internal class RecordingsView(
    val list: RecordingList,
    val rules: List<RecordingRule>,
    val channels: Map<String, String>,
    val folder: String,
    val retention: Retention,
    val allChannels: Map<String, String> = channels,
    val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
)
