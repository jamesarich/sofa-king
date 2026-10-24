package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.PolicyService
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get

/**
 * The bulk edit of the selected channels: with a policy, one edit of their overrides, previewed like any policy edit;
 * without one, the lineup entries themselves, after a confirmation that lists what changes.
 */
internal class BulkEdit(
    private val portal: Portal,
    private val reader: LineupReader,
    private val editor: PolicyEditor,
) {
    private val runtime get() = portal.runtime

    suspend fun edit(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val filter = LineupFilter.from(form)
        val ids = form.getAll("id").orEmpty().distinct()
        val page = "/admin/lineup${queryOf(filter)}"
        val policy = runtime.policy
        val refusal = when {
            ids.isEmpty() -> "Select at least one channel first."
            policy != null -> byPolicy(call, session, form, policy, ids, page)
            else -> byLineup(call, session, form, ids, page)
        }
        if (refusal != null) {
            call.html(
                lineupPage(
                    portal.chrome(call, session, Flash("Not saved: $refusal", Tone.BAD)),
                    LineupView(reader.lineupView(filter), selected = ids.toSet()),
                ),
                HttpStatusCode.BadRequest,
            )
        }
    }

    /** One policy edit of the stations' overrides; returns why it cannot be made, or null once it has answered. */
    private suspend fun byPolicy(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        policy: PolicyService,
        ids: List<String>,
        page: String,
    ): String? {
        val stations = policy.lastApplied()?.stations.orEmpty().associateBy { it.id }
        val keys = ids.mapNotNull { stations[it]?.key }
        val missing = ids.size - keys.size
        if (missing > 0) {
            return "${plural(missing, "selected channel")} ${if (missing == 1) "is" else "are"} not in the last " +
                "policy run; reload the page."
        }
        // A refused edit is explained by the notice; the page keeps the selection so the operator can correct it.
        val filter = LineupFilter.from(form)
        val edit = PolicyEditor.Edit(page, { bulkEdit(form["op"], form["target"], keys) }, "/admin/lineup/bulk", page) {
                chrome,
                _,
                confirm,
            ->
            lineupPage(chrome, LineupView(reader.lineupView(filter), selected = ids.toSet(), confirm = confirm))
        }
        editor.edit(call, session, form, edit)
        return null
    }

    /** The lineup entries themselves, once confirmed; returns why they cannot change, or null once it has answered. */
    private suspend fun byLineup(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        ids: List<String>,
        page: String,
    ): String? {
        val op = form["op"]
        val update = when (op) {
            "hide" -> UpdateLineupEntry(enabled = false)

            "show" -> UpdateLineupEntry(enabled = true)

            "group" -> form["target"]?.trim().orEmpty().let {
                if (it.isEmpty()) UpdateLineupEntry(clearGroup = true) else UpdateLineupEntry(group = it)
            }

            else -> return "Unknown edit ${op ?: ""}".trim()
        }
        val entries = ids.mapNotNull { runtime.lineup.entry(it) }
        if (form["confirm"] == "yes") {
            for (entry in entries) runtime.lineup.update(entry.id, update)
            session.flash.set(Flash("Changed ${plural(entries.size, "channel")}."))
            seeOther(call, page)
        } else {
            val describe = when (op) {
                "hide" -> "hide ${plural(entries.size, "channel")}"
                "show" -> "show ${plural(entries.size, "channel")}"
                else -> "move ${plural(entries.size, "channel")} to ${update.group ?: "no group"}"
            }
            val confirm =
                Confirm(
                    "/admin/lineup/bulk",
                    describe,
                    form.editFields(),
                    "",
                    impactOf(op, entries, update),
                    false,
                    page,
                )
            val view = LineupView(reader.lineupView(LineupFilter.from(form)), selected = ids.toSet(), confirm = confirm)
            call.html(lineupPage(portal.chrome(call, session), view))
        }
        return null
    }

    /** What a bulk [update] of [entries] turns on, off or moves, as a policy edit's preview shows it. */
    private fun impactOf(op: String?, entries: List<LineupEntryRecord>, update: UpdateLineupEntry) = Impact(
        entries.count { it.enabled },
        entries.count { update.enabled ?: it.enabled },
        if (op == "group") entries.map { Moved(it.name, it.group, update.group) } else emptyList(),
        emptyList(), emptyList(),
        if (op == "hide") entries.filter { it.enabled }.map { Moved(it.name, "on", "off") } else emptyList(),
        if (op == "show") entries.filter { !it.enabled }.map { Moved(it.name, "off", "on") } else emptyList(),
        emptyList(), emptyList(),
    )
}
