package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.StationOverride
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.http.parameters
import io.ktor.http.set
import io.ktor.server.routing.get
import io.ktor.server.routing.query

/** The lineup page's filters, read from a query or from the hidden fields of a row action. */
internal data class LineupFilter(
    val q: String = "",
    val group: String = "",
    val state: String = "",
    val required: Boolean = false,
    val page: Int = 1,
    val guide: String = "",
    /** Stations with an override in the policy. */
    val override: Boolean = false,
    /** Entries the newest run that changed anything touched; matched by the view, which reads the change log. */
    val changed: Boolean = false,
    /** A column of [SORTS], or empty for lineup order; [dir] is `asc` or `desc`. */
    val sort: String = "",
    val dir: String = "",
) {
    fun admits(row: StationRow): Boolean =
        found(row) && (group.isEmpty() || row.entry.group == group) && (!required || row.required) &&
            (!override || row.overridden) && guideAdmits(row.guideStatus) && stateAdmits(row)

    private fun found(row: StationRow): Boolean = q.isEmpty() || row.entry.name.contains(q, ignoreCase = true) ||
        row.entry.number == q || row.station?.key?.contains(q, ignoreCase = true) == true

    private fun guideAdmits(status: String): Boolean = when (guide) {
        "available" -> status == "available"
        "missing" -> status != "available"
        else -> true
    }

    private fun stateAdmits(row: StationRow): Boolean = when (state) {
        "enabled" -> row.entry.enabled
        "hidden" -> !row.entry.enabled
        "unverified" -> row.station?.disabledReason == "unverified"
        else -> true
    }

    /** The rows in the order [sort] and [dir] ask for; numbers sort by value, names without case. */
    fun order(): Comparator<StationRow> {
        val by: Comparator<StationRow> = when (sort) {
            "number" -> compareBy<StationRow> {
                it.entry.number.toDoubleOrNull() ?: Double.MAX_VALUE
            }.thenBy { it.entry.number }

            "name" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.entry.name }

            "group" -> compareBy<StationRow> {
                it.entry.group == null
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.entry.group.orEmpty() }

            "sources" -> compareBy<StationRow> { it.entry.offeringIds.size }.thenBy { it.healthy }

            "guide" -> compareBy { GUIDE_ORDER.indexOf(it.guideStatus) }

            "state" -> compareBy<StationRow> { !it.entry.enabled }.thenBy { it.state }

            else -> compareBy { 0 }
        }
        return if (dir == "desc") by.reversed() else by
    }

    fun query(page: Int = this.page): String = listOf(
        "q" to q, "group" to group, "state" to state, "guide" to guide, "required" to if (required) "true" else "",
        "override" to if (override) "true" else "", "changed" to if (changed) "true" else "", "sort" to sort,
        "dir" to dir,
        "page" to if (page > 1) page.toString() else "",
    ).filter { it.second.isNotEmpty() }.formUrlEncode()

    companion object {
        fun from(parameters: Parameters) = LineupFilter(
            parameters["q"]?.trim().orEmpty().take(QUERY_LIMIT),
            parameters["group"].orEmpty().take(QUERY_LIMIT),
            parameters["state"]?.takeIf { it in STATES }.orEmpty(),
            parameters["required"] == "true",
            parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            parameters["guide"]?.takeIf { it in GUIDE_STATES }.orEmpty(),
            parameters["override"] == "true",
            parameters["changed"] == "true",
            parameters["sort"]?.takeIf { it in SORTS }.orEmpty(),
            parameters["dir"]?.takeIf { it == "desc" }.orEmpty(),
        )

        private const val QUERY_LIMIT = 200
        private val STATES = setOf("enabled", "hidden", "unverified")
        private val GUIDE_STATES = setOf("available", "missing")
        internal val SORTS = setOf("number", "name", "group", "sources", "guide", "state")
        private val GUIDE_ORDER = listOf("available", "stale", "empty", "unassigned")
    }
}

/** One lineup entry on the Channels page, with its policy station, guide state, healthy source count and override. */
internal class StationRow(
    val entry: LineupEntryRecord,
    val station: PolicyStation?,
    val guideStatus: String,
    val healthy: Int = 0,
    val overridden: Boolean = false,
) {
    val required: Boolean get() = station?.required == true
    val state: String get() = if (entry.enabled) {
        "on"
    } else {
        "off: " +
            (station?.disabledReason ?: entry.autoDisabledReason ?: "hidden")
    }

    /**
     * The row's tint: bad for an on-air station with sources and none healthy, warn for one
     * whose guide ended; null otherwise.
     */
    val tint: Tone? get() = when {
        !entry.enabled -> null
        entry.offeringIds.isNotEmpty() && healthy == 0 -> Tone.BAD
        guideStatus == "stale" || guideStatus == "empty" -> Tone.WARN
        else -> null
    }
}

/** Whether the entry for station [key] holds something a person set: anything but the number a run wrote back. */
internal fun isOverride(key: String, station: StationOverride, policy: LineupPolicy): Boolean = with(station) {
    name != null || group != null || logo != null || guide != null || enabled != null || required ||
        sources.isNotEmpty() ||
        prefer.isNotEmpty() ||
        exclude.isNotEmpty() ||
        (number != null && key !in policy.numbering.assigned)
}
