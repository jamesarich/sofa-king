package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.CandidateFilter
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.PolicyUnmappedCategory
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import io.ktor.http.Parameters
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.dataList
import kotlinx.html.dialog
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.hidden
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.map
import kotlinx.html.mark
import kotlinx.html.numberInput
import kotlinx.html.ol
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.s
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One edit to the policy file's JSON, named for the notice and the confirm step, and the page [section] it belongs to.
 */
internal class PolicyEdit(val describe: String, val section: String, val change: (JsonObject) -> JsonObject)

private fun bad(message: String): Nothing = throw ApiFailure(400, "INVALID_REQUEST", message)

private fun Parameters.text(name: String, what: String = name): String =
    this[name]?.trim()?.takeIf { it.isNotEmpty() } ?: bad("$what is required")

private fun Parameters.index(): Int = this["index"]?.toIntOrNull() ?: bad("index is required")

/** [tree] with the object at [key] replaced by [edit] of it, created when missing. */
private fun JsonObject.withObject(key: String, edit: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
    val inner = (this[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    edit(inner)
    return JsonObject(toMutableMap().apply { put(key, JsonObject(inner)) })
}

private fun MutableMap<String, JsonElement>.objectAt(key: String, edit: (MutableMap<String, JsonElement>) -> Unit) {
    val inner = (this[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    edit(inner)
    if (inner.isEmpty()) remove(key) else put(key, JsonObject(inner))
}

private fun MutableMap<String, JsonElement>.arrayAt(key: String, edit: (MutableList<JsonElement>) -> Unit) {
    val inner = (this[key] as? JsonArray)?.toMutableList() ?: mutableListOf()
    edit(inner)
    if (inner.isEmpty()) remove(key) else put(key, JsonArray(inner))
}

private fun category(raw: String): String = raw.trim().lowercase()

/**
 * The taxonomy edits the groups page offers: number blocks, the default group, the category map,
 * the weak list and name patterns.
 */
internal fun groupsEdit(form: Parameters): PolicyEdit = when (val op = form["op"]) {
    "block" -> {
        val group = form.text("group", "A group name")
        val start = form["start"]?.trim()?.toIntOrNull() ?: bad("The first number must be a whole number")
        PolicyEdit("number $group from $start", "blocks") { tree ->
            tree.withObject("numbering") { n ->
                n.objectAt("groups") {
                    it[group] =
                        JsonPrimitive(start)
                }
            }
        }
    }

    "block-remove" -> {
        val group = form.text("group", "A group name")
        PolicyEdit("remove the number block of $group", "blocks") { tree ->
            tree.withObject("numbering") { n -> n.objectAt("groups") { it.remove(group) } }
        }
    }

    "default" -> {
        val group = form["group"]?.trim().orEmpty()
        PolicyEdit(
            if (group.isEmpty()) {
                "let ungrouped stations keep their raw category"
            } else {
                "put ungrouped stations in $group"
            },
            "blocks",
        ) { tree ->
            tree.withObject("groups") { g ->
                if (group.isEmpty()) {
                    g.remove("default")
                } else {
                    g["default"] =
                        JsonPrimitive(group)
                }
            }
        }
    }

    "map" -> {
        val key = category(form.text("category", "A category"))
        val group = form.text("group", "A group")
        PolicyEdit("map \"$key\" to $group", "map") { tree ->
            tree.withObject("groups") { g ->
                g.objectAt("map") { m ->
                    m[key] =
                        JsonPrimitive(group)
                }
            }
        }
    }

    "map-remove" -> {
        val key = form.text("category", "A category")
        PolicyEdit("unmap \"$key\"", "map") { tree ->
            tree.withObject("groups") { g -> g.objectAt("map") { it.remove(key) } }
        }
    }

    "weak" -> {
        val key = category(form.text("category", "A category"))
        PolicyEdit("make \"$key\" a weak category", "weak") { tree ->
            tree.withObject("groups") { g ->
                g.arrayAt("weak") { list ->
                    if (list.none { it.jsonPrimitive.content == key }) list.add(JsonPrimitive(key))
                    list.sortBy { it.jsonPrimitive.content }
                }
            }
        }
    }

    "weak-remove" -> {
        val key = form.text("category", "A category")
        PolicyEdit("let \"$key\" vote like any category", "weak") { tree ->
            tree.withObject("groups") { g ->
                g.arrayAt("weak") { list ->
                    list.removeAll { it.jsonPrimitive.content == key }
                }
            }
        }
    }

    "pattern" -> {
        val name = form["name"]?.trim()?.takeIf { it.isNotEmpty() }
        val raw = form["rawGroup"]?.trim()?.takeIf { it.isNotEmpty() }
        val group = form.text("group", "A group")
        if (name == null && raw == null) bad("A pattern needs a name pattern, a category pattern or both")
        PolicyEdit("add a pattern for $group", "patterns") { tree ->
            tree.withObject("groups") { g ->
                g.arrayAt("patterns") {
                    it.add(
                        buildJsonObject {
                            name?.let { put("name", it) }
                            raw?.let { put("rawGroup", it) }
                            put("group", group)
                        },
                    )
                }
            }
        }
    }

    "pattern-remove" -> {
        val index = form.index()
        PolicyEdit("remove pattern ${index + 1}", "patterns") { tree ->
            tree.withObject("groups") { g ->
                g.arrayAt("patterns") {
                    if (index in
                        it.indices
                    ) {
                        it.removeAt(index)
                    } else {
                        bad("That pattern is gone; reload the page")
                    }
                }
            }
        }
    }

    else -> bad("Unknown edit ${op ?: ""}".trim())
}

/**
 * The provider-entry edits the providers page offers: order, the default for stations an entry leads, add and remove.
 */
internal fun providersEdit(form: Parameters, policy: LineupPolicy): PolicyEdit {
    val entries = policy.providers
    fun entryAt(index: Int) = entries.getOrNull(index) ?: bad("That provider entry is gone; reload the page")
    fun edit(describe: String, change: (MutableList<JsonElement>) -> Unit) = PolicyEdit(describe, "entries") { tree ->
        val list = (tree["providers"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        change(list)
        JsonObject(tree.toMutableMap().apply { put("providers", JsonArray(list)) })
    }
    return when (val op = form["op"]) {
        "move" -> {
            val index = form.index()
            val entry = entryAt(index)
            val to = if (form["to"] == "up") index - 1 else index + 1
            if (to !in entries.indices) bad("That entry is already ${if (to < 0) "first" else "last"}")
            edit("move ${entry.id} ${if (to < index) "up" else "down"}") { list -> list.add(to, list.removeAt(index)) }
        }

        "enabled" -> {
            val index = form.index()
            val entry = entryAt(index)
            val enabled = form["enabled"]?.toBooleanStrictOrNull() ?: bad("enabled must be true or false")
            edit("${if (enabled) "show" else "hide"} the stations ${entry.id} leads") { list ->
                val item = (list[index] as? JsonObject)?.toMutableMap() ?: bad("That entry is not an object")
                if (enabled) item.remove("enabled") else item["enabled"] = JsonPrimitive(false)
                list[index] = JsonObject(item)
            }
        }

        "add" -> {
            val id = form.text("provider", "A provider")
            edit("take every channel of $id last") { list -> list.add(buildJsonObject { put("id", id) }) }
        }

        "remove" -> {
            val index = form.index()
            val entry = entryAt(index)
            edit("remove the ${filterSummary(entry.include, entry.exclude)} entry for ${entry.id}") { list ->
                list.removeAt(index)
            }
        }

        else -> bad("Unknown edit ${op ?: ""}".trim())
    }
}

internal fun filterSummary(include: CandidateFilter?, exclude: CandidateFilter?): String {
    fun describe(filter: CandidateFilter) = listOfNotNull(
        filter.groups.takeIf { it.isNotEmpty() }?.let { "groups ${it.joinToString()}" },
        filter.groupPatterns.takeIf { it.isNotEmpty() }?.let { "groups like ${it.joinToString()}" },
        filter.names.takeIf { it.isNotEmpty() }?.let { "names ${it.joinToString()}" },
        filter.namePatterns.takeIf { it.isNotEmpty() }?.let { "names like ${it.joinToString()}" },
    ).joinToString("; ")
    val taken = include?.let(::describe)?.takeIf { it.isNotEmpty() } ?: "every channel"
    val left = exclude?.let(::describe)?.takeIf { it.isNotEmpty() }
    return if (left == null) taken else "$taken, except $left"
}

/** One station's field before and after an edit. */
internal class Moved(val name: String, val before: String?, val after: String?)

/** What an edit does to the stations a run builds, by field. */
internal class Impact(
    val enabledBefore: Int,
    val enabledAfter: Int,
    val group: List<Moved>,
    val number: List<Moved>,
    val name: List<Moved>,
    val turnedOff: List<Moved>,
    val turnedOn: List<Moved>,
    val added: List<String>,
    val removed: List<String>,
) {
    val isEmpty: Boolean get() = group.isEmpty() && number.isEmpty() && name.isEmpty() && turnedOff.isEmpty() &&
        turnedOn.isEmpty() && added.isEmpty() && removed.isEmpty()
}

internal fun impactOf(before: List<PolicyStation>, after: List<PolicyStation>): Impact {
    val old = before.associateBy { it.key }
    val new = after.associateBy { it.key }
    val both = new.keys.filter { it in old }.sorted()
    fun moved(field: (PolicyStation) -> String?) = both.mapNotNull { key ->
        val a = old.getValue(key)
        val b = new.getValue(key)
        if (field(a) != field(b)) Moved(b.name, field(a), field(b)) else null
    }
    return Impact(
        before.count { it.enabled }, after.count { it.enabled },
        moved { it.group }, moved { it.number }, moved { it.name },
        both.filter {
            old.getValue(it).enabled && !new.getValue(it).enabled
        }.map { Moved(new.getValue(it).name, "on", "off (${new.getValue(it).disabledReason ?: "hidden"})") },
        both.filter {
            !old.getValue(it).enabled && new.getValue(it).enabled
        }.map { Moved(new.getValue(it).name, "off", "on") },
        (new.keys - old.keys).sorted().map {
            new.getValue(it).name
        },
        (old.keys - new.keys).sorted().map { old.getValue(it).name },
    )
}

/** An edit waiting for the operator: what it does, and the fields that save it against [revision]. */
internal class Confirm(
    val action: String,
    val describe: String,
    val fields: List<Pair<String, String>>,
    val revision: String,
    val impact: Impact,
    val stale: Boolean,
    val back: String,
)

/** An edit the policy refused, shown in its [section]. */
internal class EditErrors(val section: String, val messages: List<String>)

internal fun FlowContent.sectionErrors(errors: EditErrors?, section: String) {
    if (errors?.section == section) notice("Not saved. The policy would not be valid:", errors.messages)
}

internal fun FlowContent.confirmDialog(chrome: Chrome, confirm: Confirm?) {
    confirm ?: return
    div("modal") {
        div("dialog") {
            attributes["role"] = "dialog"
            attributes["aria-modal"] = "true"
            attributes["aria-labelledby"] = "confirm-title"
            h2 {
                id = "confirm-title"
                +"Save: ${confirm.describe}?"
            }
            if (confirm.stale) {
                notice(
                    "The policy changed since this page loaded, so nothing was saved. This is what the edit does " +
                        "against the policy as it is now.",
                    tone = Tone.WARN,
                )
            }
            val impact = confirm.impact
            if (impact.isEmpty) {
                p { +"No channel changes: the lineup stays as it is." }
            } else {
                p { +"Channels on: ${thousands(impact.enabledBefore)} → ${thousands(impact.enabledAfter)}." }
                fun list(title: String, items: List<Moved>) {
                    if (items.isEmpty()) return
                    h3 { +"${plural(items.size, "channel")} $title" }
                    ul("small") {
                        for (item in items.take(6)) {
                            li {
                                +item.name
                                if (item.before != null || item.after != null) {
                                    span("muted") { +": ${item.before ?: "none"} → ${item.after ?: "none"}" }
                                }
                            }
                        }
                        if (items.size > 6) li("muted") { +"and ${thousands(items.size - 6)} more" }
                    }
                }
                list("turn off", impact.turnedOff)
                list("turn on", impact.turnedOn)
                list("change group", impact.group)
                list("change number", impact.number)
                list("change name", impact.name)
                list("join the lineup", impact.added.map { Moved(it, null, null) })
                list("leave the lineup", impact.removed.map { Moved(it, null, null) })
            }
            form(action = confirm.action, method = FormMethod.post) {
                hiddenInput(name = "csrf") { value = chrome.session.csrf }
                hiddenInput(name = "revision") { value = confirm.revision }
                hiddenInput(name = "confirm") { value = "yes" }
                for ((name, value) in confirm.fields) hiddenInput(name = name) { this.value = value }
                div("actions") {
                    a(confirm.back, classes = "btn quiet") {
                        attributes["autofocus"] = "autofocus"
                        +"Cancel"
                    }
                    button(
                        type = ButtonType.submit,
                        classes = if (impact.turnedOff.isNotEmpty() || impact.removed.isNotEmpty()) {
                            "danger"
                        } else {
                            "primary"
                        },
                    ) { +"Save and apply" }
                }
            }
        }
    }
}

/**
 * A yes-or-no dialog for an action that cannot be undone: [title] asks the question, [block] adds the action's hidden
 * fields and the explanation, and the form posts to [action] with `confirm=yes`; Cancel goes back to [back].
 */
internal fun FlowContent.askDialog(
    chrome: Chrome,
    title: String,
    action: String,
    back: String,
    verb: String,
    danger: Boolean = false,
    block: FORM.() -> Unit,
) {
    div("modal") {
        div("dialog") {
            attributes["role"] = "dialog"
            attributes["aria-modal"] = "true"
            attributes["aria-labelledby"] = "confirm-title"
            h2 {
                id = "confirm-title"
                +title
            }
            form(action = action, method = FormMethod.post) {
                hiddenInput(name = "csrf") { value = chrome.session.csrf }
                hiddenInput(name = "confirm") { value = "yes" }
                block()
                div("actions") {
                    a(back, classes = "btn quiet") {
                        attributes["autofocus"] = "autofocus"
                        +"Cancel"
                    }
                    button(type = ButtonType.submit, classes = if (danger) "danger" else "primary") { +verb }
                }
            }
        }
    }
}

/**
 * The bulk edit the Channels page offers over the selected stations, by key: hide, show or set the group of each,
 * written into their overrides in one policy edit. [op] and [group] come from the form.
 */
internal fun bulkEdit(op: String?, group: String?, keys: List<String>): PolicyEdit {
    if (keys.isEmpty()) bad("Select at least one channel")
    val count = "${keys.size} ${if (keys.size == 1) "channel" else "channels"}"
    fun each(describe: String, change: (MutableMap<String, JsonElement>) -> Unit) =
        PolicyEdit(describe, "bulk") { tree ->
            val stations = (tree["stations"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            for (key in keys) {
                val station = (stations[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                change(station)
                if (station.isEmpty()) stations.remove(key) else stations[key] = JsonObject(station)
            }
            JsonObject(tree.toMutableMap().apply { put("stations", JsonObject(stations.toSortedMap())) })
        }
    return when (op) {
        "hide" -> each("hide $count") { it["enabled"] = JsonPrimitive(false) }

        "show" -> each("show $count") { it["enabled"] = JsonPrimitive(true) }

        "group" -> {
            val target = group?.trim().orEmpty()
            if (target.isEmpty()) {
                each("let the policy group $count") { it.remove("group") }
            } else {
                each("move $count to $target") { it["group"] = JsonPrimitive(target) }
            }
        }

        else -> bad("Unknown edit ${op ?: ""}".trim())
    }
}

/** A policy edit form: the CSRF token, the revision the page showed, and the [op]. */
internal fun FlowContent.editForm(
    action: String,
    chrome: Chrome,
    revision: String,
    op: String,
    classes: String = "inline",
    block: FORM.() -> Unit,
) = form(action = action, method = FormMethod.post, classes = classes) {
    attributes["hx-swap"] = "innerHTML show:none"
    hiddenInput(name = "csrf") { value = chrome.session.csrf }
    hiddenInput(name = "revision") { value = revision }
    hiddenInput(name = "op") { value = op }
    block()
}

internal class BlockRow(val group: String, val start: Int, val end: Int, val on: Int, val total: Int)

internal class GroupsView(
    val revision: String,
    val policy: LineupPolicy,
    val blocks: List<BlockRow>,
    val groupNames: List<String>,
    val unmapped: List<PolicyUnmappedCategory>,
    val unblocked: List<Pair<String, Int>>,
)

private fun FlowContent.groupSelect(
    choices: List<String>,
    selected: String? = null,
    id: String? = null,
    empty: String? = null,
) = select {
    name = "group"
    if (id != null) this.id = id
    required = empty == null
    if (empty != null) {
        option {
            value = ""
            this.selected = selected.isNullOrEmpty()
            +empty
        }
    }
    for (group in choices) {
        option {
            value = group
            this.selected = group == selected
            +group
        }
    }
}

internal fun groupsPage(
    chrome: Chrome,
    view: GroupsView?,
    errors: EditErrors? = null,
    confirm: Confirm? = null,
): String = layout("Groups", chrome, "/admin/groups") {
    pageHead(
        "Groups",
        "How stations are grouped and numbered. Each change is written into the lineup policy and applied; a change " +
            "that moves channels asks first.",
    )
    if (view == null) {
        div("notice warn") {
            +"Groups need a lineup policy. Add policy.json to the data directory, or set SOFA_POLICY, and restart."
        }
        return@layout
    }
    tabs(chrome, "/admin/groups", CHANNEL_TABS)
    val action = "/admin/groups/edit"
    val p = view.policy
    panel("Groups and number blocks", id = "blocks", aside = {
        span("muted") {
            +(
                "Blocks hold up to ${thousands(p.numbering.groupSize)} numbers; off stations number from " +
                    "${thousands(p.numbering.floor)}" +
                    if (p.numbering.followGroup) "; stations follow their group" else ""
                )
        }
    }) {
        sectionErrors(errors, "blocks")
        if (view.blocks.isEmpty()) {
            p("muted") {
                +"No group has a number block, so numbers come from the providers or from ${p.numbering.floor} up."
            }
        } else {
            div("scroll") {
                table {
                    caption("sr") { +"Number blocks" }
                    thead {
                        tr {
                            col("Group")
                            col("Numbers", "num")
                            col("Channels on", "num")
                            col("First number")
                            col("Action", "sr")
                        }
                    }
                    tbody {
                        for (block in view.blocks) {
                            tr {
                                th {
                                    attributes["scope"] = "row"
                                    a(
                                        "/admin/lineup?group=" + java.net.URLEncoder.encode(
                                            block.group,
                                            Charsets.UTF_8,
                                        ),
                                        classes = "rowlink",
                                    ) {
                                        +block.group
                                    }
                                }
                                td("num nowrap") { +"${block.start}–${block.end}" }
                                td("num") {
                                    +"${thousands(block.on)}"
                                    span("muted") { +" of ${thousands(block.total)}" }
                                }
                                td {
                                    editForm(action, chrome, view.revision, "block", classes = "actions") {
                                        hiddenInput(name = "group") { value = block.group }
                                        numberInput(name = "start", classes = "w-number") {
                                            value = block.start.toString()
                                            min = "1"
                                            max = "999999"
                                            required = true
                                            attributes["aria-label"] = "First number of ${block.group}"
                                        }
                                        button(type = ButtonType.submit, classes = "small") { +"Save" }
                                    }
                                }
                                td {
                                    editForm(action, chrome, view.revision, "block-remove") {
                                        hiddenInput(name = "group") { value = block.group }
                                        button(type = ButtonType.submit, classes = "small quiet") {
                                            attributes["aria-label"] =
                                                "Remove the block of ${block.group}"
                                            +"Remove"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (view.unblocked.isNotEmpty()) {
            p("muted small") {
                +"Groups in the lineup without a block: "
                +view.unblocked.joinToString { (group, count) -> "$group (${thousands(count)})" }
            }
        }
        div("stack spaced") {
            editForm(action, chrome, view.revision, "block", classes = "formgrid") {
                div("field") {
                    label {
                        htmlFor = "b-group"
                        +"New group"
                    }
                    textInput(name = "group") {
                        id = "b-group"
                        required = true
                        maxLength = "128"
                        attributes["list"] =
                            "group-names"
                        placeholder = "Weather"
                    }
                }
                div("field") {
                    label {
                        htmlFor = "b-start"
                        +"First number"
                    }
                    numberInput(name = "start") {
                        id = "b-start"
                        required = true
                        min = "1"
                        max = "999999"
                    }
                }
                div { button(type = ButtonType.submit) { +"Add block" } }
            }
            editForm(action, chrome, view.revision, "default", classes = "formgrid") {
                div("field") {
                    label {
                        htmlFor = "d-group"
                        +"Group for stations nothing groups"
                    }
                    groupSelect(view.groupNames, p.groups.default, "d-group", empty = "Keep the provider's category")
                }
                div { button(type = ButtonType.submit) { +"Save default" } }
            }
        }
        dataList {
            id = "group-names"
            for (group in view.groupNames) option { value = group }
        }
    }
    panel("Unmapped categories", id = "unmapped", aside = {
        span("muted") { +plural(view.unmapped.size, "category", "categories") }
    }) {
        sectionErrors(errors, "unmapped")
        if (view.unmapped.isEmpty()) {
            p("muted") { +"Every provider category maps to a group or is weak." }
            return@panel
        }
        p("muted small") {
            +(
                "Categories no map entry or pattern names, from the last run, most stations first. Map one to give " +
                    "its stations a vote, or mark it weak when it names no genre."
                )
        }
        div("scroll") {
            table {
                caption("sr") { +"Unmapped categories" }
                thead {
                    tr {
                        col("Category")
                        col("Provider")
                        col("Stations", "num")
                        col("Example")
                        col("Map to")
                        col("Action", "sr")
                    }
                }
                tbody {
                    for (row in view.unmapped) {
                        tr {
                            th {
                                attributes["scope"] = "row"
                                +row.category
                            }
                            td { +row.provider }
                            td("num") { +thousands(row.stations) }
                            td("muted") { +row.example }
                            td {
                                editForm(action, chrome, view.revision, "map", classes = "actions") {
                                    hiddenInput(name = "category") { value = row.category }
                                    groupSelect(view.groupNames, empty = "Pick a group")
                                    button(type = ButtonType.submit, classes = "small") {
                                        attributes["aria-label"] =
                                            "Map ${row.category}"
                                        +"Map"
                                    }
                                }
                            }
                            td {
                                editForm(action, chrome, view.revision, "weak") {
                                    hiddenInput(name = "category") { value = row.category }
                                    button(type = ButtonType.submit, classes = "small quiet") {
                                        attributes["aria-label"] =
                                            "Mark ${row.category} weak"
                                        +"Weak"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    panel("Category map", id = "map", aside = {
        span("muted") { +plural(p.groups.map.size, "category", "categories") }
    }) {
        sectionErrors(errors, "map")
        p("muted small") {
            +(
                "A provider's category votes for the group it maps to; the group with the most providers' votes " +
                    "wins. Keys are lowercase, or provider:category for one provider."
                )
        }
        val byGroup = p.groups.map.entries.groupBy({ it.value }, { it.key }).toSortedMap()
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
                                    editForm(action, chrome, view.revision, "map-remove") {
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
        editForm(action, chrome, view.revision, "map", classes = "formgrid spaced") {
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
    }
    div("cols") {
        panel("Weak categories", id = "weak") {
            sectionErrors(errors, "weak")
            p("muted small") { +"Broad buckets such as Featured or TV vote only when no other category does." }
            if (p.groups.weak.isEmpty()) {
                p("muted") { +"None." }
            } else {
                div("chips") {
                    for (key in p.groups.weak) {
                        span("tag") {
                            +key
                            editForm(action, chrome, view.revision, "weak-remove") {
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
            editForm(action, chrome, view.revision, "weak", classes = "formgrid spaced") {
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
        panel("Name patterns", id = "patterns") {
            sectionErrors(errors, "patterns")
            p("muted small") {
                +(
                    "Case-insensitive regexes, first match wins. A name pattern groups a station ahead of any vote; " +
                        "a category pattern turns a category into a vote."
                    )
            }
            if (p.groups.patterns.isEmpty()) {
                p("muted") { +"None." }
            } else {
                ol {
                    p.groups.patterns.forEachIndexed { index, pattern ->
                        li {
                            +listOfNotNull(
                                pattern.name?.let {
                                    "name /$it/"
                                },
                                pattern.rawGroup?.let { "category /$it/" },
                            ).joinToString(" and ")
                            +" → ${pattern.group} "
                            editForm(action, chrome, view.revision, "pattern-remove") {
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
            editForm(action, chrome, view.revision, "pattern", classes = "formgrid spaced") {
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
        }
    }
    confirmDialog(chrome, confirm)
}

/**
 * The providers page's policy section: the entries in priority order, with the edits that
 * reorder, hide, add and remove them.
 */
internal fun MAIN.providerEntries(chrome: Chrome, view: ProvidersView, errors: EditErrors?, confirm: Confirm?) {
    val entries = view.entries ?: return
    val revision = view.revision ?: return
    val action = "/admin/providers/edit"
    panel("Priority", id = "entries", aside = {
        span("muted") { +"The first entry that takes a channel leads its station" }
    }) {
        sectionErrors(errors, "entries")
        if (entries.isEmpty()) {
            p("muted") { +"The policy has no provider entries, so no channel joins the lineup except through pins." }
        } else {
            div("scroll") {
                table {
                    caption("sr") { +"Provider entries, in priority order" }
                    thead {
                        tr {
                            col("#", "num")
                            col("Provider")
                            col("Takes")
                            col("Its stations")
                            col("Order")
                            col("Action", "sr")
                        }
                    }
                    tbody {
                        entries.forEachIndexed { index, entry ->
                            tr {
                                td("num") { +(index + 1).toString() }
                                th {
                                    attributes["scope"] = "row"
                                    +entry.id
                                }
                                td("small") { +filterSummary(entry.include, entry.exclude) }
                                td {
                                    editForm(action, chrome, revision, "enabled", classes = "actions") {
                                        hiddenInput(name = "index") { value = index.toString() }
                                        hiddenInput(name = "enabled") { value = (!entry.enabled).toString() }
                                        if (entry.enabled) chip("On", Tone.OK) else chip("Hidden", Tone.OFF)
                                        button(type = ButtonType.submit, classes = "small quiet") {
                                            attributes["aria-label"] =
                                                (
                                                    if (entry.enabled) {
                                                        "Hide the stations entry ${index + 1} leads"
                                                    } else {
                                                        "Show the stations entry ${index + 1} leads"
                                                    }
                                                    )
                                            +(if (entry.enabled) "Hide" else "Show")
                                        }
                                    }
                                }
                                td {
                                    div("actions") {
                                        for ((to, label) in listOf("up" to "↑", "down" to "↓")) {
                                            val disabled =
                                                (to == "up" && index == 0) ||
                                                    (to == "down" && index == entries.lastIndex)
                                            editForm(action, chrome, revision, "move") {
                                                hiddenInput(name = "index") { value = index.toString() }
                                                hiddenInput(name = "to") { value = to }
                                                button(type = ButtonType.submit, classes = "small") {
                                                    this.disabled = disabled
                                                    attributes["aria-label"] = "Move ${entry.id} entry ${index + 1} $to"
                                                    +label
                                                }
                                            }
                                        }
                                    }
                                }
                                td {
                                    editForm(action, chrome, revision, "remove") {
                                        hiddenInput(name = "index") { value = index.toString() }
                                        button(type = ButtonType.submit, classes = "small quiet") {
                                            attributes["aria-label"] =
                                                "Remove ${entry.id} entry ${index + 1}"
                                            +"Remove"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val listed = entries.filter { it.include == null && it.exclude == null }.map { it.id }.toSet()
        val addable = view.rows.map { it.status.id }.filter { it !in listed }.sorted()
        if (addable.isNotEmpty()) {
            editForm(action, chrome, revision, "add", classes = "formgrid spaced") {
                div("field") {
                    label {
                        htmlFor = "e-add"
                        +"Take every channel of"
                    }
                    select {
                        name = "provider"
                        id = "e-add"
                        for (id in addable) {
                            option {
                                value = id
                                +id
                            }
                        }
                    }
                }
                div { button(type = ButtonType.submit) { +"Add entry last" } }
            }
        }
    }
    confirmDialog(chrome, confirm)
}
