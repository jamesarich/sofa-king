package dev.jamesrich.sofaking.backend.admin

import io.ktor.http.Parameters
import kotlinx.html.A
import kotlinx.html.a
import kotlinx.html.form
import kotlinx.html.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The taxonomy edits the groups page offers: number blocks, the default group, the category map,
 * the weak list and name patterns.
 */
internal fun groupsEdit(form: Parameters): PolicyEdit = when (val op = form["op"]) {
    "block" -> numberBlock(form)
    "block-remove" -> removeBlock(form)
    "default" -> defaultGroup(form)
    "map" -> mapCategory(form)
    "map-remove" -> unmapCategory(form)
    "weak" -> weakCategory(form)
    "weak-remove" -> votingCategory(form)
    "pattern" -> addPattern(form)
    "pattern-remove" -> removePattern(form)
    else -> invalidEdit("Unknown edit ${op ?: ""}".trim())
}

private fun numberBlock(form: Parameters): PolicyEdit {
    val group = form.text("group", "A group name")
    val start = form["start"]?.trim()?.toIntOrNull() ?: invalidEdit("The first number must be a whole number")
    return PolicyEdit("number $group from $start", "blocks") { tree ->
        tree.withObject("numbering") { n -> n.objectAt("groups") { it[group] = JsonPrimitive(start) } }
    }
}

private fun removeBlock(form: Parameters): PolicyEdit {
    val group = form.text("group", "A group name")
    return PolicyEdit("remove the number block of $group", "blocks") { tree ->
        tree.withObject("numbering") { n -> n.objectAt("groups") { it.remove(group) } }
    }
}

private fun defaultGroup(form: Parameters): PolicyEdit {
    val group = form["group"]?.trim().orEmpty()
    val describe = if (group.isEmpty()) {
        "let ungrouped stations keep their raw category"
    } else {
        "put ungrouped stations in $group"
    }
    return PolicyEdit(describe, "blocks") { tree ->
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

private fun mapCategory(form: Parameters): PolicyEdit {
    val key = category(form.text("category", "A category"))
    val group = form.text("group", "A group")
    return PolicyEdit("map \"$key\" to $group", "map") { tree ->
        tree.withObject("groups") { g -> g.objectAt("map") { m -> m[key] = JsonPrimitive(group) } }
    }
}

private fun unmapCategory(form: Parameters): PolicyEdit {
    val key = form.text("category", "A category")
    return PolicyEdit("unmap \"$key\"", "map") { tree ->
        tree.withObject("groups") { g -> g.objectAt("map") { it.remove(key) } }
    }
}

private fun weakCategory(form: Parameters): PolicyEdit {
    val key = category(form.text("category", "A category"))
    return PolicyEdit("make \"$key\" a weak category", "weak") { tree ->
        tree.withObject("groups") { g ->
            g.arrayAt("weak") { list ->
                if (list.none { it.jsonPrimitive.content == key }) list.add(JsonPrimitive(key))
                list.sortBy { it.jsonPrimitive.content }
            }
        }
    }
}

private fun votingCategory(form: Parameters): PolicyEdit {
    val key = form.text("category", "A category")
    return PolicyEdit("let \"$key\" vote like any category", "weak") { tree ->
        tree.withObject("groups") { g ->
            g.arrayAt("weak") { list -> list.removeAll { it.jsonPrimitive.content == key } }
        }
    }
}

private fun addPattern(form: Parameters): PolicyEdit {
    val name = form["name"]?.trim()?.takeIf { it.isNotEmpty() }
    val raw = form["rawGroup"]?.trim()?.takeIf { it.isNotEmpty() }
    val group = form.text("group", "A group")
    if (name == null && raw == null) invalidEdit("A pattern needs a name pattern, a category pattern or both")
    val pattern = buildJsonObject {
        name?.let { put("name", it) }
        raw?.let { put("rawGroup", it) }
        put("group", group)
    }
    return PolicyEdit("add a pattern for $group", "patterns") { tree ->
        tree.withObject("groups") { g -> g.arrayAt("patterns") { it.add(pattern) } }
    }
}

private fun removePattern(form: Parameters): PolicyEdit {
    val index = form.index()
    return PolicyEdit("remove pattern ${index + 1}", "patterns") { tree ->
        tree.withObject("groups") { g ->
            g.arrayAt("patterns") {
                if (index in it.indices) it.removeAt(index) else invalidEdit("That pattern is gone; reload the page")
            }
        }
    }
}
