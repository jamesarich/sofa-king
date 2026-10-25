package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.health.FailureClass
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.health.Observation
import kotlinx.html.FlowContent
import kotlinx.html.li
import kotlinx.html.ul

/** How many probe results the Channel page shows for each source. */
internal const val RECENT_PROBES = 3

/** What a probe failure of [failure]'s class means, as `source-health.md#failure-classes` sorts the evidence. */
internal fun failureWords(failure: FailureClass?): String = when (failure) {
    FailureClass.OFFERING -> "the source failed: not found, no stream, or a picture that would not decode"

    FailureClass.PROVIDER ->
        "the provider did not answer: a server error, a connection, DNS or TLS failure, " +
            "or too slow"

    FailureClass.AUTH -> "the provider refused access (401 or 403)"

    FailureClass.RATE_LIMIT -> "the provider asked for fewer requests (429)"

    FailureClass.LOCAL -> "this server could not run the check: a missing decoder, permission, memory or disk"

    FailureClass.DEVICE -> "the tuner device failed"

    null -> "the probe failed"
}

/**
 * Why a source that is not healthy is where it is: the newest failure among [recent] for a suspect or quarantined
 * source, what probation waits for, and null for the rest.
 */
internal fun healthWhy(state: HealthState?, recent: List<Observation>): String? {
    val failure = recent.firstOrNull { !it.qualifiedSuccess }
        ?: state?.failures?.lastOrNull()
    return when (state?.status) {
        HealthStatus.SUSPECT, HealthStatus.QUARANTINED -> failure?.let { failureWords(it.failureClass) }
        HealthStatus.PROBATION -> "passed once since quarantine; one more pass ten minutes on restores it"
        else -> null
    }?.replaceFirstChar { it.uppercase() }
}

/** [recent] probe results as a short list, newest first: passed, or failed and why, with when. */
internal fun FlowContent.recentProbes(recent: List<Observation>, chrome: Chrome) = ul("probes small muted") {
    for (observation in recent) {
        li {
            +(if (observation.qualifiedSuccess) "Passed " else "Failed ")
            ago(observation.observedAt.toString(), chrome.now)
            if (!observation.qualifiedSuccess) +": ${failureWords(observation.failureClass)}"
        }
    }
}

/**
 * Why a channel has no healthy source, for its row on the Channels page: every source disabled by hand, the newest
 * probe failure among [recent], or no pass yet.
 */
internal fun stationWhy(
    offeringIds: List<String>,
    health: Map<String, HealthState>,
    recent: Map<String, List<Observation>>,
): String {
    if (offeringIds.all { health[it]?.manuallyDisabled == true }) return "every source is disabled by hand"
    val failure = offeringIds.flatMap { recent[it].orEmpty() }.filter { !it.qualifiedSuccess }
        .maxByOrNull { it.observedAt }
    return failure?.let { failureWords(it.failureClass) } ?: "no source has passed a probe yet"
}
