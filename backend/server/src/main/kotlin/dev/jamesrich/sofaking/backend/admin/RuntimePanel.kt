package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.settings.SettingType
import dev.jamesrich.sofaking.backend.settings.Settings
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
            runtimeSave(view.proposed != null && runtimeChanged(view.current, view.proposed))
        }
    }

/**
 * The runtime settings' fields, one per registry entry, filled with the proposed or current values, each saying where
 * its value is from and what it does.
 */
private class RuntimeFields(private val view: RuntimeSettingsView) {
    private val values = view.proposed ?: view.current.byName()
    private val environment = view.environment.byName()

    fun render(div: DIV) = with(div) {
        for (def in Settings.runtime) {
            div("field") {
                label {
                    htmlFor = def.name
                    +def.label
                }
                when (val type = def.type) {
                    is SettingType.Choice -> select {
                        id = def.name
                        name = def.name
                        for (choice in type.values) {
                            option {
                                value = choice
                                selected = choice == values[def.name]
                                +choice
                            }
                        }
                    }

                    else -> numberInput(name = def.name) {
                        id = def.name
                        value = values.getValue(def.name)
                        min = def.range.first.toString()
                        max = def.range.last.toString()
                        required = true
                    }
                }
                span("hint") {
                    +(
                        if (def.name in view.saved) {
                            "Saved here; the environment has ${environment.getValue(def.name)}."
                        } else {
                            "From the environment."
                        }
                        )
                }
                span("hint") { +def.help }
            }
        }
    }
}
