package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.VirtualAiring
import dev.jamesrich.sofaking.backend.api.VirtualChannel
import dev.jamesrich.sofaking.backend.api.VirtualSlot
import dev.jamesrich.sofaking.backend.api.VirtualSource
import dev.jamesrich.sofaking.backend.virtual.SourceKind
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.checkBoxInput
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.small
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One virtual channel as the list shows it, with what it airs now. */
internal class VirtualRow(val channel: VirtualChannel, val now: String?)

/**
 * The Virtual tab: whether the `virtual` source is set up and in the policy, the channels, and the form that adds one
 * or changes [editing], with its schedule ahead.
 */
internal class VirtualView(
    val configured: Boolean,
    /** Null without a policy; otherwise whether its providers list a `virtual` source. */
    val inPolicy: Boolean?,
    val rows: List<VirtualRow>,
    val sources: List<Pair<VirtualSource, String>>,
    val editing: VirtualChannel?,
    val schedule: List<VirtualAiring>,
    val zone: ZoneId,
)

internal fun virtualChannelsPage(chrome: Chrome, view: VirtualView): String =
    layout("Virtual channels", chrome, "/admin/virtual") {
        pageHead(
            "Virtual channels",
            "Channels the server plays from your libraries on a schedule, as live channels in the guide, the outputs " +
                "and the DVR. Each airs whole items one after another, in order or shuffled.",
        )
        tabs(chrome, "/admin/virtual", channelTabs(view.inPolicy != null))
        setupNotice(view)
        panel("Channels", id = "channels") {
            if (view.rows.isEmpty()) {
                p("muted") { +"No virtual channels yet." }
            } else {
                channelTable(chrome, view.rows)
            }
        }
        val editing = view.editing
        panel(if (editing == null) "Add a channel" else "Change ${editing.name}", id = "edit") {
            if (view.sources.isEmpty()) {
                p("muted") { +"Add a library first; a channel plays what the libraries hold." }
            } else {
                channelForm(chrome, view, editing)
            }
        }
        if (editing != null) schedulePanel(view)
    }

private fun FlowContent.setupNotice(view: VirtualView) {
    val message = when {
        !view.configured ->
            "Turn on Virtual channels on the Sources page, which restarts the server, so the " +
                "channels here reach the lineup."

        view.inPolicy == false ->
            "Add the virtual channels source to the policy's providers on the Sources page, " +
                "so the lineup takes its channels."

        else -> return
    }
    div("notice warn") {
        attributes["role"] = "status"
        +"$message "
        a("/admin/providers") { +"Sources" }
    }
}

private fun FlowContent.channelTable(chrome: Chrome, rows: List<VirtualRow>) = div("scroll") {
    table {
        caption("sr") { +"Virtual channels" }
        thead {
            tr {
                col("Number")
                col("Name")
                col("Slots")
                col("On now")
                col("Action")
            }
        }
        tbody {
            for (row in rows) {
                val channel = row.channel
                tr {
                    td { +(channel.number ?: "") }
                    th {
                        attributes["scope"] = "row"
                        +channel.name
                    }
                    td { +plural(channel.slots.size, "slot") }
                    td { +(row.now ?: "") }
                    td("nowrap") {
                        a("/admin/virtual?edit=${channel.id}#edit", classes = "btn small") {
                            attributes["aria-label"] = "Change ${channel.name}"
                            +"Change"
                        }
                        +" "
                        postForm("/admin/virtual/${channel.id}/delete", chrome.session) {
                            button(type = ButtonType.submit, classes = "small quiet") {
                                attributes["aria-label"] = "Delete ${channel.name}"
                                +"Delete"
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.channelForm(chrome: Chrome, view: VirtualView, editing: VirtualChannel?) {
    val action = editing?.let { "/admin/virtual/${it.id}" } ?: "/admin/virtual"
    postForm(action, chrome.session, classes = "stack") {
        textField("virtual-name", "name", "Name", editing?.name, "Saturday cartoons") {
            required = true
            maxLength = "100"
        }
        textField("virtual-number", "number", "Number (optional)", editing?.number, "2001") { maxLength = "9" }
        val slots = editing?.slots.orEmpty()
        for (index in 0 until slots.size + EMPTY_SLOTS) slotFields(view, index, slots.getOrNull(index))
        p("muted small") {
            +(
                "Give every slot a start time (${view.zone.id}) for a daily grid, each running until the next one " +
                    "starts, or leave them all blank to take turns, one item each. A live channel needs start times " +
                    "and plays its whole slot. Leave a slot's source blank to remove it; save to get more empty slots."
                )
        }
        val library = view.sources.filter { it.first.kind != SourceKind.CHANNEL }
        sourceSelect("virtual-filler", "filler", "Filler between items (optional)", library, editing?.filler)
        div("field") {
            label {
                htmlFor = "virtual-pad"
                +"Fill to the next mark"
            }
            select {
                id = "virtual-pad"
                name = "padMinutes"
                for ((minutes, text) in PAD_CHOICES) {
                    option {
                        value = minutes.toString()
                        selected = (editing?.padMinutes ?: 0) == minutes
                        +text
                    }
                }
            }
        }
        breakFields(library, editing?.breaks)
        div("actions") {
            button(type = ButtonType.submit, classes = "primary") { +(if (editing == null) "Add" else "Save") }
            if (editing != null) a("/admin/virtual", classes = "btn quiet") { +"Cancel" }
        }
    }
}

private fun FORM.slotFields(view: VirtualView, index: Int, slot: VirtualSlot?) = fieldSet("slot") {
    legend { +"Slot ${index + 1}" }
    sourceSelect("slot-$index-source", "source.$index", "Plays", view.sources, slot?.source)
    div("field") {
        label {
            htmlFor = "slot-$index-start"
            +"Starts at"
        }
        input(type = InputType.time, name = "start.$index") {
            id = "slot-$index-start"
            slot?.start?.let { value = it }
        }
    }
    div("field") {
        label {
            checkBoxInput(name = "shuffle.$index") {
                value = "true"
                checked = slot?.shuffle == true
            }
            +" Shuffle"
        }
    }
}

internal fun FlowContent.sourceSelect(
    id: String,
    name: String,
    text: String,
    sources: List<Pair<VirtualSource, String>>,
    current: VirtualSource?,
) = div("field") {
    label {
        htmlFor = id
        +text
    }
    select {
        this.id = id
        this.name = name
        option {
            value = ""
            +"None"
        }
        for ((source, label) in sources) {
            option {
                value = sourceValue(source)
                selected = source == current
                +label
            }
        }
    }
}

private fun FlowContent.textField(
    id: String,
    name: String,
    text: String,
    current: String?,
    example: String,
    block: kotlinx.html.INPUT.() -> Unit,
) = div("field") {
    label {
        htmlFor = id
        +text
    }
    textInput(name = name) {
        this.id = id
        placeholder = example
        current?.let { value = it }
        block()
    }
}

private fun FlowContent.schedulePanel(view: VirtualView) = panel("Coming up", id = "schedule") {
    if (view.schedule.isEmpty()) {
        p("muted") { +"Nothing scheduled." }
        return@panel
    }
    div("scroll") {
        table {
            caption("sr") { +"Coming up" }
            thead {
                tr {
                    col("Starts (${view.zone.id})")
                    col("Ends")
                    col("Airs")
                }
            }
            tbody {
                for (airing in view.schedule) {
                    tr {
                        td("nowrap") { +clock(airing.startsAt, view.zone) }
                        td("nowrap") { +clock(airing.endsAt, view.zone) }
                        td {
                            +airing.title
                            if (airing.filler) small("muted") { +" filler" }
                        }
                    }
                }
            }
        }
    }
}

/** A source as one form value: its kind and value, such as `show:abc`. */
internal fun sourceValue(source: VirtualSource): String = "${source.kind}:${source.value}"

/** The source a form value names, or null for an empty one. */
internal fun sourceOf(value: String?): VirtualSource? {
    val text = value?.trim()?.ifEmpty { null } ?: return null
    return VirtualSource(text.substringBefore(':'), text.substringAfter(':', ""))
}

private fun clock(iso: String, zone: ZoneId): String = TIME.format(Instant.parse(iso).atZone(zone))

private val TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)
private const val EMPTY_SLOTS = 2
private val PAD_CHOICES = listOf(0 to "No filling", 15 to "Quarter hour", 30 to "Half hour", 60 to "Hour")
