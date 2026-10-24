package dev.jamesrich.sofaking.backend.admin

import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.map
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.textInput
import kotlinx.html.title

internal fun FlowContent.mapPanel(chrome: Chrome, view: GroupsView, errors: EditErrors?) =
    panel("Category map", id = "map", aside = {
        span("muted") { +plural(view.policy.groups.map.size, "category", "categories") }
    }) {
        sectionErrors(errors, "map")
        p("muted small") {
            +(
                "A provider's category votes for the group it maps to; the group with the most providers' votes " +
                    "wins. Keys are lowercase, or provider:category for one provider."
                )
        }
        val byGroup = view.policy.groups.map.entries.groupBy({ it.value }, { it.key }).toSortedMap()
        if (byGroup.isEmpty()) {
            p("muted") { +"No category is mapped." }
        } else {
            div("maplist") {
                for ((group, categories) in byGroup) {
                    div("maprow") {
                        div("mapgroup") {
                            +group
                            span("muted small") { +" · ${categories.size}" }
                        }
                        div("chips") {
                            for (key in categories.sorted()) {
                                span("tag") {
                                    +key
                                    groupEdit(chrome, view, "map-remove") {
                                        hiddenInput(name = "category") { value = key }
                                        button(type = ButtonType.submit) {
                                            attributes["aria-label"] = "Unmap $key"
                                            attributes["title"] =
                                                "Unmap"
                                            +"×"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        mapForm(chrome, view)
    }

private fun FlowContent.mapForm(chrome: Chrome, view: GroupsView) =
    groupEdit(chrome, view, "map", classes = "formgrid spaced") {
        div("field") {
            label {
                htmlFor = "m-cat"
                +"Category"
            }
            textInput(name = "category") {
                id = "m-cat"
                required =
                    true
                maxLength = "256"
                placeholder = "true crime or pluto:true crime"
            }
        }
        div("field") {
            label {
                htmlFor = "m-group"
                +"Group"
            }
            groupSelect(view.groupNames, id = "m-group", empty = "Pick a group")
        }
        div { button(type = ButtonType.submit) { +"Map category" } }
    }

internal fun FlowContent.weakPanel(chrome: Chrome, view: GroupsView, errors: EditErrors?) =
    panel("Weak categories", id = "weak") {
        sectionErrors(errors, "weak")
        p("muted small") { +"Broad buckets such as Featured or TV vote only when no other category does." }
        if (view.policy.groups.weak.isEmpty()) {
            p("muted") { +"None." }
        } else {
            div("chips") {
                for (key in view.policy.groups.weak) {
                    span("tag") {
                        +key
                        groupEdit(chrome, view, "weak-remove") {
                            hiddenInput(name = "category") { value = key }
                            button(type = ButtonType.submit) {
                                attributes["aria-label"] = "Let $key vote"
                                attributes["title"] =
                                    "Remove"
                                +"×"
                            }
                        }
                    }
                }
            }
        }
        groupEdit(chrome, view, "weak", classes = "formgrid spaced") {
            div("field") {
                label {
                    htmlFor = "w-cat"
                    +"Category"
                }
                textInput(name = "category") {
                    id = "w-cat"
                    required =
                        true
                    maxLength = "256"
                }
            }
            div { button(type = ButtonType.submit) { +"Add" } }
        }
    }

internal fun FlowContent.patternsPanel(chrome: Chrome, view: GroupsView, errors: EditErrors?) =
    panel("Name patterns", id = "patterns") {
        sectionErrors(errors, "patterns")
        p("muted small") {
            +(
                "Case-insensitive regexes, first match wins. A name pattern groups a station ahead of any vote; " +
                    "a category pattern turns a category into a vote."
                )
        }
        if (view.policy.groups.patterns.isEmpty()) {
            p("muted") { +"None." }
        } else {
            ol {
                view.policy.groups.patterns.forEachIndexed { index, pattern ->
                    li {
                        +listOfNotNull(
                            pattern.name?.let {
                                "name /$it/"
                            },
                            pattern.rawGroup?.let { "category /$it/" },
                        ).joinToString(" and ")
                        +" → ${pattern.group} "
                        groupEdit(chrome, view, "pattern-remove") {
                            hiddenInput(name = "index") { value = index.toString() }
                            button(type = ButtonType.submit, classes = "small quiet") {
                                attributes["aria-label"] =
                                    "Remove pattern ${index + 1}"
                                +"Remove"
                            }
                        }
                    }
                }
            }
        }
        patternForm(chrome, view)
    }

private fun FlowContent.patternForm(chrome: Chrome, view: GroupsView) =
    groupEdit(chrome, view, "pattern", classes = "formgrid spaced") {
        div("field") {
            label {
                htmlFor = "p-name"
                +"Name pattern"
            }
            textInput(name = "name") {
                id = "p-name"
                maxLength =
                    "256"
                placeholder = "\\bnoticias\\b"
            }
        }
        div("field") {
            label {
                htmlFor = "p-raw"
                +"Category pattern"
            }
            textInput(name = "rawGroup") {
                id =
                    "p-raw"
                maxLength = "256"
            }
        }
        div("field") {
            label {
                htmlFor = "p-group"
                +"Group"
            }
            groupSelect(view.groupNames, id = "p-group", empty = "Pick a group")
        }
        div { button(type = ButtonType.submit) { +"Add pattern" } }
    }
