package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.settings.SettingDef
import dev.jamesrich.sofaking.backend.settings.SettingType
import dev.jamesrich.sofaking.backend.settings.Settings
import kotlinx.html.DIV
import kotlinx.html.FIELDSET
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.numberInput
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.textInput

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
            div("runtime-settings") { RuntimeFields(view).render(this) }
            runtimeSave(view.proposed != null && runtimeChanged(view.current, view.proposed))
        }
    }

/**
 * The runtime settings' fields, filled with the proposed or current values, one row per registry entry under its
 * area's heading: the label and what it does on the left, the control on the right. Where the value comes from shows
 * only when it was saved here; the environment is the usual place and goes unsaid.
 */
private class RuntimeFields(private val view: RuntimeSettingsView) {
    private val values = view.proposed ?: view.current.byName()
    private val environment = view.environment.byName()

    fun render(div: DIV) = with(div) {
        for ((area, defs) in Settings.runtime.groupBy { it.area }) {
            fieldSet("runtime-group") {
                legend { +area.title }
                for (def in defs) row(def)
            }
        }
    }

    private fun FIELDSET.row(def: SettingDef) = div("setting-row") {
        div("setting-text") {
            label {
                htmlFor = def.name
                +def.label
            }
            span("hint") { +def.help }
        }
        div("setting-control") {
            control(def)
            if (def.name in view.saved) {
                span("hint") { +"Saved here; the environment has ${environment.getValue(def.name)}." }
            }
        }
    }

    private fun DIV.control(def: SettingDef) {
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

            SettingType.Text -> textInput(name = def.name) {
                id = def.name
                value = values.getValue(def.name)
            }

            else -> numberInput(name = def.name) {
                id = def.name
                value = values.getValue(def.name)
                min = def.range.first.toString()
                max = def.range.last.toString()
                required = true
            }
        }
    }
}
