package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.GuideMatch
import dev.jamesrich.sofaking.backend.api.GuideMatchChoice
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

/** The policy's own pages: merges, guide matches, required channels and groups. */
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
        route.get("/guide-matches") { call.html(guideMatchesPage(chrome(), guideMatchesView())) }
        route.action("/guide-matches/accept") { session, form ->
            guideMatch(call, session) { policy ->
                val choice = choiceOf(form)
                policy.guideMatches.accept(choice)
                "Accepted ${choice.channelId} for ${choice.station}."
            }
        }
        route.action("/guide-matches/dismiss") { session, form ->
            guideMatch(call, session) { policy ->
                val choice = choiceOf(form)
                policy.guideMatches.dismiss(choice)
                "Dismissed ${choice.channelId} for ${choice.station}."
            }
        }
        route.action("/guide-matches/accept-all") { session, _ ->
            guideMatch(call, session) { policy ->
                "Accepted ${plural(policy.guideMatches.acceptAll(null).accepted, "guide match", "guide matches")}."
            }
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

    /** Runs [write], then shows what it did, or why it failed, on Guide matches. */
    private suspend fun guideMatch(
        call: ApplicationCall,
        session: AdminSession,
        write: suspend (PolicyService) -> String,
    ) {
        val policy = runtime.policy
            ?: throw ApiFailure(HttpStatusCode.NotFound, "POLICY_NOT_CONFIGURED", "No lineup policy is configured")
        val failure = try {
            session.flash.set(Flash("${write(policy)} The policy was applied."))
            null
        } catch (e: ApiFailure) {
            e
        }
        if (failure == null) {
            seeOther(call, "/admin/guide-matches")
        } else {
            call.html(
                guideMatchesPage(
                    portal.chrome(call, session, Flash("Not saved: ${failure.message}", Tone.BAD)),
                    guideMatchesView(),
                    failure.message,
                ),
                failure.status,
            )
        }
    }

    private fun guideMatchesView(): GuideMatchesView? {
        val policy = runtime.policy ?: return null
        val settings = policy.policy.guideMatches
        return GuideMatchesView(
            policy.guideMatches.proposals(),
            settings.acceptAbove,
            settings.ignorePrefixes,
            settings.ignoreSuffixes,
            ran = policy.lastApplied() != null,
        )
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

/** The station and guide channel a Guide matches form posts. */
private fun choiceOf(form: Parameters): GuideMatchChoice {
    val (station, guideId, channelId) = listOf("station", "guideId", "channelId").map { form[it]?.trim().orEmpty() }
    if (station.isEmpty() || guideId.isEmpty() || channelId.isEmpty()) {
        throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "A station and a guide channel are required")
    }
    return GuideMatchChoice(station, guideId, channelId)
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

internal class GuideMatchesView(
    val matches: List<GuideMatch>,
    val acceptAbove: Double,
    val ignorePrefixes: List<String>,
    val ignoreSuffixes: List<String>,
    val ran: Boolean,
)
