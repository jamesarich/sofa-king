package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.backend.dvr.KEEP_RANGE
import dev.jamesrich.sofaking.backend.dvr.PRIORITY_RANGE
import dev.jamesrich.sofaking.backend.http.scheduleChecked
import dev.jamesrich.sofaking.contract.CreateRecordingRule
import dev.jamesrich.sofaking.contract.RecordingRule
import dev.jamesrich.sofaking.contract.RuleMatch
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.UpdateRecordingRule
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.Serializable
import java.time.Clock

// The DVR actions, listed only to the admin token: what the portal's Recordings page and the recording routes do.

private const val CHANNEL = "channelId"
private const val RECORDING = "recordingId"
private const val RULE = "id"
private const val OP = "op"
private val PADDING = 0..Dvr.MAX_PADDING_SECONDS
private val RULE_OPS = listOf("create", "update", "delete")
private val MATCH_KINDS = listOf(Wire.RuleMatchKind.TITLE, Wire.RuleMatchKind.TEAM, Wire.RuleMatchKind.KEYWORD)

/** Scheduling an airing that is already scheduled answers the one that exists. */
private val SCHEDULE = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = false,
    idempotentHint = true,
    openWorldHint = false,
)

private val DVR_CHANGE = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = true,
    idempotentHint = false,
    openWorldHint = false,
)

@Serializable internal class RuleDeleted(val ok: Boolean, val message: String, val recordingsRemoved: Int)

internal fun Server.dvrTools(context: McpContext) {
    val dvr = context.runtime.dvr ?: return
    scheduleTool(dvr, context.clock)
    cancelTool(dvr)
    ruleTool(dvr)
}

private fun Server.scheduleTool(dvr: Dvr, clock: Clock) {
    tool(
        "schedule_recording",
        "Schedule one recording, as the apps' Record does. Takes a channelId (a lineup entry id) and the startsAt " +
            "of a guide program, both from on_later or guide_now, to record that program with the usual padding. " +
            "Add endsAt (at most ${Dvr.MAX_MANUAL.toHours()} hours after startsAt) and optionally a title to record " +
            "that time window instead, guide or not. Answers the recording; one already scheduled for that start " +
            "is answered as it is. recordings shows the schedule and any conflict.",
        schema(
            CHANNEL to string("The lineup entry id, from on_later, guide_now or lineup"),
            "startsAt" to string("ISO-8601 instant the program or window starts"),
            "endsAt" to string("ISO-8601 instant a manual window ends; leave out to record a guide program"),
            "title" to string("What to call a manual window; defaults to the channel's name"),
            required = listOf(CHANNEL, "startsAt"),
        ),
        SCHEDULE,
    ) { arguments ->
        val channel = arguments.string(CHANNEL) ?: invalid("channelId is required; call on_later or guide_now")
        val startsAt = arguments.string("startsAt") ?: invalid("startsAt is required")
        val request = ScheduleRecording(channel, startsAt, arguments.string("endsAt"), arguments.string("title"))
        dvr.scheduleChecked(request, clock.instant()).row()
    }
}

private fun Server.cancelTool(dvr: Dvr) {
    tool(
        "cancel_recording",
        "Cancel a scheduled recording, stop a running one (keeping what it recorded) or delete a finished one. " +
            "Deleting a finished or protected recording also deletes its file, so that needs deleteFile true as " +
            "well. Takes a recordingId from recordings. Needs confirm true.",
        schema(
            RECORDING to string("The recording's id, from recordings"),
            CONFIRM to boolean("Required true: a canceled recording does not record"),
            "deleteFile" to boolean("Required true to delete a finished or protected recording and its file"),
            required = listOf(RECORDING),
        ),
        DVR_CHANGE,
    ) { arguments ->
        val id = arguments.string(RECORDING) ?: invalid("recordingId is required; call recordings for ids")
        val recording = dvr.recording(id)
        val keepsFile = recording.state == Wire.RecordingState.COMPLETED ||
            recording.state == Wire.RecordingState.FAILED ||
            recording.protected
        arguments.confirmed("the recording '${recording.title}' (${recording.state}) would be canceled or deleted")
        if (keepsFile && arguments.boolean("deleteFile") != true) {
            throw ApiFailure(
                HttpStatusCode.BadRequest,
                "FILE_DELETE_REQUIRED",
                "Nothing was done: '${recording.title}' is ${recording.state}" +
                    (if (recording.protected) " and protected" else "") +
                    ", and deleting it deletes its file. Tell the operator, then call again with deleteFile true",
            )
        }
        dvr.delete(id)
        ActionResult(true, "Removed the ${recording.state} recording '${recording.title}'")
    }
}

private fun Server.ruleTool(dvr: Dvr) {
    tool(
        "manage_recording_rule",
        "Create, update or delete a rule the DVR records by, as the recording-rules routes do; recording_rules " +
            "lists them. create needs a title and records airings of that title, or with match team or keyword " +
            "the airings whose title (and for keyword, description) holds any of terms, in categories when given. " +
            "update changes channelId (anyChannel true clears it), newOnly, keep, the paddings and priority; a " +
            "rule's title and match never change. delete removes the rule and its scheduled recordings, keeps " +
            "what it recorded, and needs confirm true.",
        schema(
            OP to choice("What to do", RULE_OPS),
            RULE to string("The rule's id, from recording_rules, for update and delete"),
            "title" to string("What the rule records or is called, for create"),
            "match" to choice("What the rule matches; title by default", MATCH_KINDS),
            "terms" to strings("Words a team or keyword rule matches, any of them"),
            "categories" to strings("Guide categories a team or keyword rule is limited to"),
            CHANNEL to string("A lineup entry id to limit the rule to; leave out for any channel"),
            "anyChannel" to boolean("update only: true clears the rule's channel"),
            "newOnly" to boolean("Skip airings the guide marks as repeats"),
            "keep" to integer("How many finished recordings to keep, 0 for all", KEEP_RANGE, 0),
            "startPaddingSeconds" to
                integer("Seconds to start early", PADDING, Dvr.DEFAULT_START_PADDING.seconds.toInt()),
            "endPaddingSeconds" to integer("Seconds to end late", PADDING, Dvr.DEFAULT_END_PADDING.seconds.toInt()),
            "priority" to integer("A higher priority wins a conflict", PRIORITY_RANGE, 0),
            CONFIRM to boolean("Required true for delete"),
            required = listOf(OP),
        ),
        DVR_CHANGE,
    ) { arguments ->
        when (arguments.choice(OP, RULE_OPS) ?: invalid("op is create, update or delete")) {
            "create" -> createRule(dvr, arguments).asJson()
            "update" -> updateRule(dvr, arguments).asJson()
            else -> deleteRule(dvr, arguments).asJson()
        }
    }
}

private suspend fun createRule(dvr: Dvr, arguments: Arguments): RecordingRule {
    val kind = arguments.choice("match", MATCH_KINDS) ?: Wire.RuleMatchKind.TITLE
    val terms = arguments.strings("terms").orEmpty()
    if (kind != Wire.RuleMatchKind.TITLE && terms.isEmpty()) invalid("a $kind rule needs terms")
    val match = RuleMatch(kind, terms, arguments.strings("categories").orEmpty()).takeIf {
        kind != Wire.RuleMatchKind.TITLE
    }
    return dvr.rules.create(
        CreateRecordingRule(
            title = arguments.string("title") ?: invalid("title is required to create a rule"),
            channelId = arguments.string(CHANNEL),
            newOnly = arguments.boolean("newOnly") ?: true,
            keep = arguments.intOrNull("keep", KEEP_RANGE) ?: 0,
            startPaddingSeconds = arguments.intOrNull("startPaddingSeconds", PADDING),
            endPaddingSeconds = arguments.intOrNull("endPaddingSeconds", PADDING),
            priority = arguments.intOrNull("priority", PRIORITY_RANGE) ?: 0,
            match = match,
        ),
    )
}

private suspend fun updateRule(dvr: Dvr, arguments: Arguments): RecordingRule {
    if (arguments.string("title") != null || arguments.string("match") != null) {
        invalid("a rule's title and match never change; delete the rule and create another")
    }
    return dvr.rules.update(
        arguments.string(RULE) ?: invalid("id is required; call recording_rules for ids"),
        UpdateRecordingRule(
            channelId = arguments.string(CHANNEL),
            anyChannel = arguments.boolean("anyChannel"),
            newOnly = arguments.boolean("newOnly"),
            keep = arguments.intOrNull("keep", KEEP_RANGE),
            startPaddingSeconds = arguments.intOrNull("startPaddingSeconds", PADDING),
            endPaddingSeconds = arguments.intOrNull("endPaddingSeconds", PADDING),
            priority = arguments.intOrNull("priority", PRIORITY_RANGE),
        ),
    )
}

private suspend fun deleteRule(dvr: Dvr, arguments: Arguments): RuleDeleted {
    val id = arguments.string(RULE) ?: invalid("id is required; call recording_rules for ids")
    arguments.confirmed("the rule and the recordings it scheduled would be removed; what it recorded stays")
    val scheduled = dvr.recordings().recordings.count { it.ruleId == id && it.state == Wire.RecordingState.SCHEDULED }
    dvr.rules.delete(id)
    return RuleDeleted(true, "Deleted the rule; its finished recordings stay", scheduled)
}
