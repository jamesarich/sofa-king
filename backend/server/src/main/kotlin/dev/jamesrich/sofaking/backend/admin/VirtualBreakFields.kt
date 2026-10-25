package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.VirtualBreaks
import dev.jamesrich.sofaking.backend.api.VirtualSource
import kotlinx.html.FORM
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.numberInput
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.textArea

/** The form fields of a channel's [breaks], choosing their item from [sources]. */
internal fun FORM.breakFields(sources: List<Pair<VirtualSource, String>>, breaks: VirtualBreaks?) = fieldSet("slot") {
    legend { +"Breaks" }
    div("field") {
        label {
            htmlFor = "virtual-breaks"
            +"Air a break"
        }
        select {
            id = "virtual-breaks"
            name = "breaks.every"
            option {
                value = ""
                selected = breaks == null
                +"Never"
            }
            for ((minutes, text) in BREAK_CHOICES) {
                option {
                    value = minutes.toString()
                    selected = breaks?.everyMinutes == minutes
                    +text
                }
            }
        }
    }
    sourceSelect("virtual-break-source", "breaks.source", "Break item (optional)", sources, breaks?.source)
    div("field") {
        label {
            htmlFor = "virtual-break-slides"
            +"Slides, one image URL per line (optional)"
        }
        textArea(rows = "3") {
            id = "virtual-break-slides"
            name = "breaks.slides"
            placeholder = "http://homeassistant.local:8123/local/chores.png"
            +(breaks?.slides.orEmpty().joinToString("\n"))
        }
    }
    div("field") {
        label {
            htmlFor = "virtual-break-seconds"
            +"Seconds each slide shows"
        }
        numberInput(name = "breaks.slideSeconds") {
            id = "virtual-break-seconds"
            min = "3"
            max = "60"
            value = (breaks?.slideSeconds ?: DEFAULT_SLIDE_SECONDS).toString()
        }
    }
    p("muted small") {
        +(
            "A break airs after an item: one item of the break source, then each slide, fetched as it airs, " +
                "such as a snapshot Home Assistant saves to its www folder. It goes before the filler, and only " +
                "where it fits."
            )
    }
}

private val BREAK_CHOICES = listOf(
    0 to "After every item",
    15 to "Every 15 minutes",
    30 to "Every 30 minutes",
    60 to "Every hour",
)
private const val DEFAULT_SLIDE_SECONDS = 10
