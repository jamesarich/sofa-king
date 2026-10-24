package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.logging.LogSettings
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.backend.settings.RuntimeValues
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span

internal fun FlowContent.runtimePanel(chrome: Chrome, view: RuntimeSettingsView) =
    panel("Runtime settings", id = "runtime") {
        p("muted small") {
            +(
                "These apply as soon as they are saved, survive restarts and take precedence over the environment. " +
                    "Saving the environment's own value puts the setting back to following the environment."
                )
        }
        if (view.errors.isNotEmpty()) notice("Not saved:", view.errors)
        form(action = "/admin/settings/runtime", method = FormMethod.post, classes = "stack") {
            id = "runtime-form"
            // With JavaScript, every change asks the server whether the form differs from what runs;
            // without it the button is Save.
            attributes["hx-post"] = "/admin/settings/runtime/check"
            attributes["hx-trigger"] = "change, load"
            attributes["hx-target"] = "#runtime-save"
            attributes["hx-select"] = "#runtime-save"
            attributes["hx-swap"] = "outerHTML"
            hiddenInput(name = "csrf") { value = chrome.session.csrf }
            div("runtime") { RuntimeFields(view).render(this) }
            p("muted small") {
                +(
                    "Plex reads a device's tuner count when it adds the device, so a change here shows in Plex after " +
                        "the device is re-added; the server enforces it at once. " +
                        "Streams per device cannot exceed the sessions at once. " +
                        "Learned ad breaks are ones the server finds in a stream with no ad " +
                        "markers, such as a long silent slate: shadow only logs them (ADDETECT), on also " +
                        "shows the break screen over them. The ad listener plays channels nobody is " +
                        "watching, headless, so the server learns their ads."
                    )
            }
            runtimeSave(view.proposed != null && runtimeChanged(view.current, view.proposed))
        }
    }

/** The runtime settings' fields, filled with the proposed or current values, each noting where its value is from. */
private class RuntimeFields(private val view: RuntimeSettingsView) {
    private val values = view.proposed ?: view.current.byName()
    private val environment = view.environment

    fun render(div: DIV) = with(div) {
        choice("r-level", RuntimeValues.LOG_LEVEL, "Log level", LogSettings.LEVELS, environment.logLevel)
        number("r-days", RuntimeValues.GUIDE_DAYS, "XMLTV days ahead", ServerConfig.GUIDE_DAYS_RANGE) {
            hint(RuntimeValues.GUIDE_DAYS, environment.guideDays)
        }
        number(
            "r-plex",
            RuntimeValues.PLEX_SYNC_MINUTES,
            "Plex sync every (minutes)",
            ServerConfig.PLEX_SYNC_MINUTES_RANGE,
        ) {
            if (view.plex) {
                hint(RuntimeValues.PLEX_SYNC_MINUTES, environment.plexSyncMinutes)
            } else {
                span("hint") { +"Plex sync is not set up; this applies once SOFA_PLEX_URL and SOFA_PLEX_TOKEN are." }
            }
        }
        number("r-tuners", RuntimeValues.HDHR_TUNERS, "Streams per HDHomeRun device", ServerConfig.MAX_SESSIONS_RANGE) {
            hint(RuntimeValues.HDHR_TUNERS, environment.hdhrTuners)
        }
        number("r-sessions", RuntimeValues.MAX_SESSIONS, "Sessions at once", ServerConfig.MAX_SESSIONS_RANGE) {
            hint(RuntimeValues.MAX_SESSIONS, environment.maxSessions)
        }
        number(
            "r-free",
            RuntimeValues.RECORDINGS_FREE_GIB,
            "Recordings: keep free (GiB)",
            ServerConfig.RECORDINGS_FREE_GIB_RANGE,
        ) { hint(RuntimeValues.RECORDINGS_FREE_GIB, environment.recordingsFreeGib) }
        number(
            "r-keep",
            RuntimeValues.RECORDINGS_KEEP_DAYS,
            "Recordings: keep for (days, 0 for no limit)",
            ServerConfig.RECORDINGS_KEEP_DAYS_RANGE,
        ) { hint(RuntimeValues.RECORDINGS_KEEP_DAYS, environment.recordingsKeepDays) }
        val modes = LearnedBreaks.entries.map { it.setting }
        choice("r-learned", RuntimeValues.LEARNED_BREAKS, "Learned ad breaks", modes, environment.learnedBreaks)
        number(
            "r-listeners",
            RuntimeValues.AD_LISTENERS,
            "Ad listener: channels at once (0 for off)",
            ServerConfig.AD_LISTENERS_RANGE,
        ) { hint(RuntimeValues.AD_LISTENERS, environment.adListeners) }
    }

    private fun DIV.number(id: String, name: String, label: String, range: IntRange, note: DIV.() -> Unit) =
        div("field") {
            label {
                htmlFor = id
                +label
            }
            numberInput(name = name) {
                this.id = id
                value = values.getValue(name)
                min = range.first.toString()
                max = range.last.toString()
                required = true
            }
            note()
        }

    private fun DIV.choice(id: String, name: String, label: String, choices: List<String>, environment: String) =
        div("field") {
            label {
                htmlFor = id
                +label
            }
            select {
                this.id = id
                this.name = name
                for (choice in choices) {
                    option {
                        value = choice
                        selected = choice == values[name]
                        +choice
                    }
                }
            }
            hint(name, environment)
        }

    /** Whether [name] is saved here, over the environment's [environment], or follows the environment. */
    private fun DIV.hint(name: String, environment: Any) = span("hint") {
        +(if (name in view.saved) "Saved here; the environment has $environment." else "From the environment.")
    }
}
