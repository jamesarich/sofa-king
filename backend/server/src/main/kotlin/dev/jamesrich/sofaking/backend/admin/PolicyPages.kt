package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.PolicyUnmappedCategory
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.PolicyService
import dev.jamesrich.sofaking.backend.store.allHealth
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.accept
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** The policy's own pages: merges, required channels and groups. */
internal class PolicyPages(private val portal: Portal, private val editor: PolicyEditor) {
    private val runtime get() = portal.runtime
    private val reader = LineupReader(portal)

    fun install(route: Route) = with(portal) {
        route.get("/merges") { call.html(mergesPage(chrome(), mergesView())) }
        route.action("/merges/never") { session, form ->
            merge(call, session, form, "Kept apart") { policy, from, to -> policy.edits.neverMerge(from, to, null) }
        }
        route.action("/merges/accept") { session, form ->
            merge(call, session, form, "Merged") { policy, from, to -> policy.edits.addAlias(from, to, null) }
        }
        route.action("/merges/dismiss") { session, form ->
            merge(call, session, form, "Kept apart") { policy, from, to -> policy.edits.neverMerge(from, to, null) }
        }
        route.get("/required") { call.html(requiredPage(chrome(), requiredView())) }
        route.get("/groups") { call.html(groupsPage(chrome(), groupsView())) }
        route.action("/groups/edit") { session, form ->
            val edit = PolicyEditor.Edit("/admin/groups", { groupsEdit(form) }) { chrome, errors, confirm ->
                groupsPage(chrome, groupsView(), errors, confirm)
            }
            editor.edit(call, session, form, edit)
        }
    }

    private suspend fun merge(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        verb: String,
        write: suspend (PolicyService, String, String) -> Unit,
    ) {
        val policy = runtime.policy
            ?: throw ApiFailure(HttpStatusCode.NotFound, "POLICY_NOT_CONFIGURED", "No lineup policy is configured")
        val from = form["from"]?.trim().orEmpty()
        val to = form["to"]?.trim().orEmpty()
        val failure = try {
            if (from.isEmpty() || to.isEmpty()) {
                throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Both keys are required")
            }
            write(policy, from, to)
            null
        } catch (e: ApiFailure) {
            e
        }
        if (failure == null) {
            session.flash.set(Flash("$verb: $from and $to. The policy was applied."))
            seeOther(call, "/admin/merges")
        } else {
            call.html(
                mergesPage(
                    portal.chrome(call, session, Flash("Not saved: ${failure.message}", Tone.BAD)),
                    mergesView(),
                    failure.message,
                ),
                failure.status,
            )
        }
    }

    private suspend fun mergesView(): MergesView? {
        val policy = runtime.policy ?: return null
        val document = policy.document()
        val findings = policy.findings()
        val suggestions = findings.suggestedAliases.mapNotNull { line ->
            SUGGESTION.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }
        return MergesView(
            document.revision,
            document.policy.names.learned,
            document.policy.names.never,
            suggestions,
            findings.findings,
            findings.unmappedCategories,
        )
    }

    private suspend fun requiredView(): RequiredView? {
        val policy = runtime.policy ?: return null
        val applied = policy.lastApplied() ?: return RequiredView(emptyList(), ran = false)
        val health = runtime.store.allHealth()
        val overrides = policy.policy.stations
        val rows = applied.stations.filter { it.required }.map { station ->
            val counts = station.offeringIds.groupingBy { health[it]?.status?.name ?: "UNVERIFIED" }.eachCount()
            RequiredRow(station, overrides[station.key]?.sources.orEmpty(), counts.toSortedMap())
        }
        return RequiredView(rows, ran = true)
    }

    private suspend fun groupsView(): GroupsView? {
        val policy = runtime.policy ?: return null
        val document = policy.document()
        val p = document.policy
        val rows = reader.rows()
        val byGroup = rows.groupBy { it.entry.group }
        val starts = p.numbering.groups.entries.sortedBy { it.value }
        val blocks = starts.mapIndexed { i, (group, start) ->
            val next = starts.getOrNull(i + 1)?.value ?: Int.MAX_VALUE
            val members = byGroup[group].orEmpty()
            BlockRow(
                group,
                start,
                minOf(start.toLong() + p.numbering.groupSize - 1, next.toLong() - 1).toInt(),
                members.count { it.entry.enabled },
                members.size,
            )
        }
        val unblocked = byGroup.filterKeys { it != null && it !in p.numbering.groups }
            .map { (group, members) -> group!! to members.size }
            .sortedByDescending { it.second }
        return GroupsView(
            document.revision,
            p,
            blocks,
            reader.groupChoices(rows),
            policy.lastApplied()?.result?.unmappedCategories.orEmpty(),
            unblocked,
        )
    }
}

private val SUGGESTION = Regex("(.+): possible alias of (.+)")

internal class MergesView(
    val revision: String,
    val learned: Map<String, String>,
    val never: List<List<String>>,
    val suggestions: List<Pair<String, String>>,
    val findings: List<String>,
    val unmapped: List<PolicyUnmappedCategory>,
)
