package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LoadedPolicy
import dev.jamesrich.sofaking.backend.catalog.PolicyService
import dev.jamesrich.sofaking.backend.catalog.parsePolicy
import dev.jamesrich.sofaking.backend.catalog.policyBytes
import dev.jamesrich.sofaking.backend.catalog.policyRevision
import dev.jamesrich.sofaking.backend.catalog.validatePolicy
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files

/**
 * A taxonomy, provider or bulk edit, made the way `PUT /api/v1/admin/policy` makes one: the whole file, written against
 * the revision the page showed, validated, then applied. An edit that changes any station, or that meets a policy that
 * moved on since the page loaded, is shown with its effect and waits for `confirm=yes`.
 */
internal class PolicyEditor(private val portal: Portal) {
    /** One edit from a page: where it posts, where it returns, and how the page shows its errors or confirmation. */
    class Edit(
        val page: String,
        val parse: (LineupPolicy) -> PolicyEdit,
        val action: String = "$page/edit",
        val back: String? = null,
        val render: suspend (Chrome, EditErrors?, Confirm?) -> String,
    )

    suspend fun edit(call: ApplicationCall, session: AdminSession, form: Parameters, edit: Edit) {
        val policy = portal.runtime.policy
        if (policy == null) {
            val flash = Flash("No lineup policy is configured.", Tone.BAD)
            call.html(edit.render(portal.chrome(call, session, flash), null, null), HttpStatusCode.Conflict)
        } else {
            Attempt(call, session, form, policy, edit).run()
        }
    }

    private sealed interface Outcome

    private class Refused(val messages: List<String>, val status: HttpStatusCode = HttpStatusCode.BadRequest) : Outcome

    private class Ready(val edit: PolicyEdit, val next: ByteArray, val candidate: LineupPolicy) : Outcome

    private inner class Attempt(
        val call: ApplicationCall,
        val session: AdminSession,
        val form: Parameters,
        val policy: PolicyService,
        val edit: Edit,
    ) {
        suspend fun run() {
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(policy.file.path) }
            val current = policyRevision(bytes)
            val refused = when (val prepared = prepare(bytes)) {
                is Refused -> prepared
                is Ready -> confirmOrWrite(current, prepared)
            }
            if (refused != null) {
                val flash = Flash("Not saved: ${refused.messages.first()}", Tone.BAD)
                val errors = EditErrors(sectionOf(form["op"]), refused.messages)
                call.html(edit.render(portal.chrome(call, session, flash), errors, null), refused.status)
            }
        }

        /** The edit applied to the file as it is now and checked, or why it cannot be. */
        private fun prepare(bytes: ByteArray): Outcome = try {
            val change = edit.parse(parsePolicy(bytes))
            val tree = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                ?: throw ApiFailure(HttpStatusCode.Conflict, "POLICY_CHANGED", "The policy file is not a JSON object")
            val next = policyBytes(change.change(tree))
            val candidate = candidateOf(next)
            val errors = validatePolicy(candidate, portal.runtime.feeds.configs.mapTo(HashSet()) { it.id })
            if (errors.isEmpty()) Ready(change, next, candidate) else Refused(errors)
        } catch (e: ApiFailure) {
            Refused(listOf(e.message))
        }

        private fun candidateOf(next: ByteArray): LineupPolicy = try {
            parsePolicy(next)
        } catch (_: IllegalArgumentException) {
            // A SerializationException is one.
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "The edit does not fit the policy schema")
        }

        /** Shows the confirmation the edit waits on, or writes and applies it; null once it has answered. */
        private suspend fun confirmOrWrite(current: String, ready: Ready): Refused? {
            val back = edit.back ?: "${edit.page}#${ready.edit.section}"
            val stale = form["revision"] != current
            val impact = if (stale || form["confirm"] != "yes") {
                impactOf(
                    policy.run().stations,
                    policy.run(LoadedPolicy(policy.file.path, ready.candidate, "candidate")).stations,
                )
            } else {
                null
            }
            if (impact == null || (!stale && impact.isEmpty)) return write(current, ready, back)
            val confirm = Confirm(edit.action, ready.edit.describe, form.editFields(), current, impact, stale, back)
            call.html(
                edit.render(portal.chrome(call, session), null, confirm),
                if (stale) HttpStatusCode.Conflict else HttpStatusCode.OK,
            )
            return null
        }

        private suspend fun write(current: String, ready: Ready, back: String): Refused? {
            val result = try {
                policy.edits.replace(current, ready.next).apply
            } catch (e: ApiFailure) {
                return if (e.status == HttpStatusCode.Conflict) {
                    Refused(
                        listOf(
                            "The policy changed while saving; nothing was saved. Reload the page and repeat the edit.",
                        ),
                        HttpStatusCode.Conflict,
                    )
                } else {
                    Refused(e.message.split("; "))
                }
            }
            session.flash.set(
                Flash(
                    "Saved: ${ready.edit.describe}. ${thousands(result.enabledStations)} channels on of " +
                        "${thousands(result.stations)}.",
                ),
            )
            seeOther(call, back)
            return null
        }
    }
}

/** The page section an edit's errors show in, by its op. */
private fun sectionOf(op: String?): String = when (op) {
    "block", "block-remove", "default" -> "blocks"
    "map", "map-remove" -> "map"
    "weak", "weak-remove" -> "weak"
    "pattern", "pattern-remove" -> "patterns"
    else -> "entries"
}
