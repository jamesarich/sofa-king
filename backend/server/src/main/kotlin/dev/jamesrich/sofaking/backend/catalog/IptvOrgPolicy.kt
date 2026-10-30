package dev.jamesrich.sofaking.backend.catalog

/** The candidates of a run with iptv-org metadata filled in, and the stations the iptv-org blocklist holds out. */
class IptvOrgEnriched(val candidates: List<FeedCandidate>, val holds: Map<String, String>)

/**
 * What the lineup policy asks of the iptv-org metadata: candidates enriched before a run builds, and, after it, the
 * stations to hold out. [findings] are the open matches and holds, each starting with the station key.
 */
interface IptvOrgPolicy {
    suspend fun enrich(candidates: List<FeedCandidate>, keys: StationKeys): IptvOrgEnriched

    fun findings(): List<String>
}
